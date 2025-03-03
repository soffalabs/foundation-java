package com.company.app;

import io.soffa.foundation.test.HttpExpect;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@ActiveProfiles("test")
@SpringBootTest(properties = {"app.openapi.access=authenticated"})
@AutoConfigureMockMvc
class SecureOpenApiTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void testOpenAPI() {
        HttpExpect test = new HttpExpect(mvc);
        test.get("/v3/api-docs")
            .expect().isForbidden();

    }
}
