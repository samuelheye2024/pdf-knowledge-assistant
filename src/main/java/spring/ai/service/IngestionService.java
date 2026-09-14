package spring.ai.service;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import spring.ai.model.DocumentMetadataKeys;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Downloads a single PDF from a public URL and adds it to the vector store.
 * The server does the fetching itself over HTTP, so this works identically
 * no matter which machine the request came from.
 *
 * <p>{@code POST /documents/ingest-url} ({@link spring.ai.controller.DocumentIngestionController})
 * and the chat-callable {@code ingestUrl} tool ({@link spring.ai.tool.IngestionTools})
 * both call {@link #ingestUrl}, so they can never drift apart.
 */
@Service
public class IngestionService {

    private static final String PDF_EXTENSION = ".pdf";

    /** Hard cap on how much of a remote URL's body we'll buffer in memory. */
    private static final long MAX_DOWNLOAD_BYTES = 25L * 1024 * 1024;

    private final VectorStore vectorStore;

    public IngestionService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * Downloads the PDF at {@code rawUrl} and adds it to the vector store.
     * Because that means fetching a URL an untrusted remote caller handed
     * us, this refuses private/internal addresses (SSRF guard), caps how
     * much it will download, and checks the downloaded bytes actually start
     * with a PDF's magic header rather than trusting the response's
     * {@code Content-Type}.
     *
     * @throws IllegalArgumentException if the URL is blank, isn't
     *         http(s), resolves to a private/internal address, or the
     *         downloaded content isn't a PDF
     * @throws IOException if the URL can't be fetched
     */
    public UrlIngestResult ingestUrl(String rawUrl) throws IOException {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new IllegalArgumentException("A non-blank URL is required");
        }

        URI uri = parseHttpUri(rawUrl.trim());
        assertNotInternalAddress(uri);

        byte[] content = download(uri);

        if (!looksLikePdf(content)) {
            throw new IllegalArgumentException("The content at that URL doesn't look like a PDF: " + rawUrl);
        }

        String filename = filenameFromUrl(uri);
        Resource resource = new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        };

        PdfDocumentReaderConfig config = PdfDocumentReaderConfig
                .builder()
                .withPagesPerDocument(1)
                .build();

        List<Document> pages = new PagePdfDocumentReader(resource, config).get();
        stampUrlSourceMetadata(pages, filename, rawUrl.trim());

        List<Document> chunks = new TokenTextSplitter().apply(pages);

        if (chunks.isEmpty()) {
            // Downloaded fine and passed the PDF magic-byte check, but the reader found
            // no extractable text -- most often a scanned/image-only PDF with no text
            // layer. Treating this as a silent success would add nothing to the vector
            // store while still reporting "ingested", which is worse than just saying so.
            throw new IllegalArgumentException(
                    "No extractable text found in the PDF at " + rawUrl
                            + " -- it may be a scanned/image-only PDF with no text layer.");
        }

        vectorStore.add(chunks);

        return new UrlIngestResult(rawUrl.trim(), filename, chunks.size());
    }

    private URI parseHttpUri(String rawUrl) {
        URI uri;
        try {
            uri = new URI(rawUrl);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Not a valid URL: " + rawUrl);
        }

        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("Only http:// and https:// URLs are supported: " + rawUrl);
        }
        if (uri.getHost() == null) {
            throw new IllegalArgumentException("URL has no host: " + rawUrl);
        }

        return uri;
    }

    /**
     * Refuses to fetch a URL that resolves to a loopback, link-local (this
     * covers cloud metadata endpoints like {@code 169.254.169.254}), private
     * (RFC 1918), or multicast address — a basic SSRF guard, since this
     * method exists specifically to fetch a URL handed to us by a caller we
     * don't otherwise trust.
     */
    private void assertNotInternalAddress(URI uri) throws IOException {
        InetAddress address;
        try {
            address = InetAddress.getByName(uri.getHost());
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Couldn't resolve host: " + uri.getHost());
        }

        if (address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isAnyLocalAddress()
                || address.isMulticastAddress()) {
            throw new IllegalArgumentException(
                    "Refusing to fetch from a private or internal address: " + uri.getHost());
        }
    }

    private byte[] download(URI uri) throws IOException {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

        HttpResponse<byte[]> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while downloading " + uri, e);
        }

        if (response.statusCode() / 100 != 2) {
            throw new IOException("Fetching " + uri + " failed with HTTP " + response.statusCode());
        }

        byte[] body = response.body();
        if (body.length == 0) {
            throw new IllegalArgumentException("No content received from " + uri);
        }
        if (body.length > MAX_DOWNLOAD_BYTES) {
            throw new IllegalArgumentException(
                    "File at " + uri + " is larger than the " + (MAX_DOWNLOAD_BYTES / (1024 * 1024)) + " MB limit");
        }

        return body;
    }

    /** Checks the PDF magic header ({@code %PDF-}) rather than trusting Content-Type, which the server can fake. */
    private boolean looksLikePdf(byte[] content) {
        return content.length >= 5
                && content[0] == '%' && content[1] == 'P' && content[2] == 'D' && content[3] == 'F' && content[4] == '-';
    }

    /** Derives a display filename from the URL's last path segment, falling back to the host name. */
    private String filenameFromUrl(URI uri) {
        String path = uri.getPath();
        if (path != null && !path.isBlank()) {
            String last = path.substring(path.lastIndexOf('/') + 1);
            if (!last.isBlank()) {
                return last.toLowerCase().endsWith(PDF_EXTENSION) ? last : last + PDF_EXTENSION;
            }
        }
        return (uri.getHost() != null ? uri.getHost() : "document") + PDF_EXTENSION;
    }

    /**
     * Stamps source/page/URL metadata explicitly (rather than relying on
     * whatever keys the reader may or may not set) so citations are
     * reliable downstream when answering RAG questions.
     */
    private void stampUrlSourceMetadata(List<Document> pages, String filename, String sourceUrl) {
        for (int i = 0; i < pages.size(); i++) {
            Map<String, Object> metadata = pages.get(i).getMetadata();
            metadata.put(DocumentMetadataKeys.SOURCE, filename);
            metadata.put(DocumentMetadataKeys.PAGE, i + 1);
            metadata.put(DocumentMetadataKeys.SOURCE_URL, sourceUrl);
        }
    }

    /** Result of a URL ingestion: the URL, the derived filename, and how many chunks were added. */
    public record UrlIngestResult(String url, String file, int chunksAdded) {
    }
}
