package com.collabeditor.realtime_editor;

import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class BaseIntegrationTest {

    @BeforeAll
    static void checkMongoAvailable() {
        try {
            java.net.Socket socket = new java.net.Socket();
            socket.connect(new java.net.InetSocketAddress("localhost", 27017), 2000);
            socket.close();
        } catch (Exception e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "MongoDB not available on localhost:27017 - skipping integration tests");
        }
    }
}
