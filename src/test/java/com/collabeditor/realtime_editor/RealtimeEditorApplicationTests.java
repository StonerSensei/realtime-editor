package com.collabeditor.realtime_editor;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class RealtimeEditorApplicationTests {

	@BeforeAll
	static void checkMongoAvailable() {
		try {
			java.net.Socket socket = new java.net.Socket();
			socket.connect(new java.net.InetSocketAddress("localhost", 27017), 2000);
			socket.close();
		} catch (Exception e) {
			org.junit.jupiter.api.Assumptions.assumeTrue(false,
					"MongoDB not available on localhost:27017 - skipping context load test");
		}
	}

	@Test
	void contextLoads() {
	}
}
