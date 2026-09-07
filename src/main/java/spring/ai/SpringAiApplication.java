package spring.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class SpringAiApplication {

	public static void main(String[] args) {
		// Both properties must be set here, before SpringApplication.run().
		// Spring Boot forces java.awt.headless=true as a system property
		// very early in its startup sequence — before application.properties
		// is even read — so setting spring.main.headless=false there is too
		// late to have any effect. Setting the system property directly,
		// before Spring touches it, is what actually works: Spring only
		// applies its own default if the property isn't already set.
		System.setProperty("java.awt.headless", "false");

		// Lets FileDialog (used by DocumentUploadController's native folder
		// picker) resolve a directory rather than a single file on macOS.
		System.setProperty("apple.awt.fileDialogForDirectories", "true");

		SpringApplication.run(SpringAiApplication.class, args);
	}

}
