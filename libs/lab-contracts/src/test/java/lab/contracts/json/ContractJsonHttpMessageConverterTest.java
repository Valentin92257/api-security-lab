package lab.contracts.json;

import lab.contracts.control.model.CreateTrialRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.ResourceHttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ContractJsonHttpMessageConverterTest {

    private final SampleController controller = new SampleController();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        JsonMapper applicationMapper = JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(
                        new ContractJsonHttpMessageConverter(new ContractJsonCodec()),
                        new ByteArrayHttpMessageConverter(),
                        new StringHttpMessageConverter(StandardCharsets.UTF_8),
                        new ResourceHttpMessageConverter(),
                        new JacksonJsonHttpMessageConverter(applicationMapper))
                .build();
    }

    @Test
    void rejectsDuplicateItemsBeforeCallingController() {
        assertThatThrownBy(() -> mvc.perform(post("/contracts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"families\":[\"BOLA\",\"BOLA\"]}")))
                .hasRootCauseInstanceOf(ContractValidationException.class);
        assertThat(controller.calls).isZero();
    }

    @Test
    void readsAndWritesContractModels() throws Exception {
        mvc.perform(post("/contracts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"families\":[\"BOLA\"]}"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"families\":[\"BOLA\"]}"));
        assertThat(controller.calls).isEqualTo(1);
    }

    @Test
    void rejectsInvalidControllerResponse() {
        assertThatThrownBy(() -> mvc.perform(get("/invalid-contract")))
                .hasRootCauseInstanceOf(ContractValidationException.class);
    }

    @Test
    void leavesTextBinaryAndFilesToTheirConverters() throws Exception {
        mvc.perform(post("/text").contentType(MediaType.TEXT_PLAIN).content("hello"))
                .andExpect(status().isOk()).andExpect(content().string("hello"));
        byte[] bytes = {0, 1, 2, -1};
        mvc.perform(post("/binary").contentType(MediaType.APPLICATION_OCTET_STREAM).content(bytes))
                .andExpect(status().isOk()).andExpect(content().bytes(bytes));
        mvc.perform(get("/file"))
                .andExpect(status().isOk()).andExpect(content().bytes(new byte[]{1, 2, 3}));
    }

    @Test
    void leavesOtherJsonModelsAndTheirMapperSettingsIntact() throws Exception {
        mvc.perform(post("/ordinary").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"hello\",\"extra\":true}"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"value\":\"hello\"}"));
    }

    @Test
    void leavesMultipartFilesToSpring() throws Exception {
        var file = new MockMultipartFile("file", "sample.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart("/upload").file(file))
                .andExpect(status().isOk()).andExpect(content().string("sample.txt"));
    }

    @Test
    void doesNotClaimOtherTypesOrMediaTypes() {
        var converter = new ContractJsonHttpMessageConverter(new ContractJsonCodec());
        assertThat(converter.canRead(String.class, MediaType.APPLICATION_JSON)).isFalse();
        assertThat(converter.canWrite(byte[].class, MediaType.APPLICATION_OCTET_STREAM)).isFalse();
        assertThat(converter.canRead(CreateTrialRequest.class, MediaType.TEXT_PLAIN)).isFalse();
    }

    @RestController
    static class SampleController {
        int calls;

        @PostMapping("/contracts")
        CreateTrialRequest contract(@RequestBody CreateTrialRequest request) {
            calls++;
            return request;
        }

        @GetMapping("/invalid-contract")
        CreateTrialRequest invalidContract() {
            return new CreateTrialRequest();
        }

        @PostMapping(value = "/text", produces = MediaType.TEXT_PLAIN_VALUE)
        String text(@RequestBody String body) {
            return body;
        }

        @PostMapping(value = "/binary", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
        byte[] binary(@RequestBody byte[] body) {
            return body;
        }

        @GetMapping("/file")
        Resource file() {
            return new ByteArrayResource(new byte[]{1, 2, 3});
        }

        @PostMapping("/ordinary")
        OrdinaryPayload ordinary(@RequestBody OrdinaryPayload body) {
            return body;
        }

        @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
                produces = MediaType.TEXT_PLAIN_VALUE)
        String upload(@RequestPart("file") MultipartFile file) {
            return file.getOriginalFilename();
        }
    }

    record OrdinaryPayload(String value) {
    }
}
