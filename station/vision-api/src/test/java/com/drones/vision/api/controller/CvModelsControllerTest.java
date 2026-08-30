package com.drones.vision.api.controller;

import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.learning.application.CatalogSource;
import com.drones.vision.learning.application.ConfigModelCatalog;
import com.drones.vision.learning.application.CvModelCatalog;
import com.drones.vision.learning.application.CvModelView;
import com.drones.vision.learning.application.ModelRegistryService;
import com.drones.vision.learning.domain.model.CvModelRecord;
import com.drones.vision.learning.domain.model.ModelAvailability;
import com.drones.vision.learning.domain.model.ModelProvenance;
import com.drones.vision.learning.domain.model.ModelRuntime;
import com.drones.vision.learning.domain.model.ModelStatus;
import com.drones.vision.learning.domain.model.ModelTaskType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Docs/plans/active/CV-SETTINGS-PLAN.md §5.2/§8 OQ5: {@code GET /api/cv/models} never errors, and
 * falls back to the deployment's static {@link ConfigModelCatalog} — whether {@link
 * ModelRegistryService} is entirely absent from the context (registry flag off) or present but
 * chooses to report {@link CatalogSource#CONFIG} itself (worker unreachable, this controller never
 * distinguishes the two — it just reads whatever {@link CvModelCatalog#source()} says).
 */
class CvModelsControllerTest {

    private static final CvModelRecord ROSTER_ENTRY = new CvModelRecord("yolo26n.pt", "latest",
            "General (people & vehicles, fast)", "general", false, List.of(), ModelTaskType.DETECT,
            ModelRuntime.PYTORCH, List.of(), ModelStatus.DRAFT, null, ModelProvenance.none(), null, null,
            Instant.EPOCH);

    @SuppressWarnings("unchecked")
    private static MockMvc mockMvcFor(ObjectProvider<ModelRegistryService> modelRegistryService,
                                       ConfigModelCatalog configModelCatalog) {
        return MockMvcBuilders.standaloneSetup(new CvModelsController(modelRegistryService, configModelCatalog))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<ModelRegistryService> absentService() {
        ObjectProvider<ModelRegistryService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<ModelRegistryService> presentService(ModelRegistryService service) {
        ObjectProvider<ModelRegistryService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(service);
        return provider;
    }

    @Test
    void modelsFallsBackToTheConfigCatalogueWhenTheRegistryServiceIsAbsent() throws Exception {
        MockMvc mockMvc = mockMvcFor(absentService(), new ConfigModelCatalog(List.of(ROSTER_ENTRY)));

        mockMvc.perform(get("/api/cv/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.models", hasSize(1)))
                .andExpect(jsonPath("$.models[0].id").value("yolo26n.pt"))
                .andExpect(jsonPath("$.models[0].displayName").value("General (people & vehicles, fast)"))
                .andExpect(jsonPath("$.models[0].kind").value("general"))
                .andExpect(jsonPath("$.models[0].openVocab").value(false))
                .andExpect(jsonPath("$.models[0].defaultLabelFilter", hasSize(0)))
                .andExpect(jsonPath("$.models[0].version").value("latest"))
                .andExpect(jsonPath("$.models[0].taskType").value("DETECT"))
                .andExpect(jsonPath("$.models[0].runtime").value("PYTORCH"))
                .andExpect(jsonPath("$.models[0].status").value("DRAFT"))
                .andExpect(jsonPath("$.models[0].availability").value("PRESENT"))
                .andExpect(jsonPath("$.models[0].source").value("config"))
                .andExpect(jsonPath("$.models[0].metrics").doesNotExist())
                .andExpect(jsonPath("$.models[0].provenance.datasetId").doesNotExist())
                .andExpect(jsonPath("$.models[0].provenance.baseModel").doesNotExist());
    }

    @Test
    void modelsNeverErrorsEvenWhenTheConfigCatalogueIsEmpty() throws Exception {
        MockMvc mockMvc = mockMvcFor(absentService(), new ConfigModelCatalog(List.of()));

        mockMvc.perform(get("/api/cv/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.models", hasSize(0)));
    }

    @Test
    void modelsUsesTheRegistryServiceWhenPresentAndTagsEveryRowWithSourceRegistry() throws Exception {
        CvModelView registryRow = new CvModelView("orion12l.pt", "3", "Military vehicles", "specialized", false,
                List.of(), ModelTaskType.DETECT, ModelRuntime.PYTORCH, List.of("truck", "tank"), ModelStatus.LIVE,
                ModelAvailability.MISSING_ON_WORKER, null, ModelProvenance.none());
        ModelRegistryService service = mock(ModelRegistryService.class);
        when(service.models()).thenReturn(new CvModelCatalog(List.of(registryRow), CatalogSource.REGISTRY));
        MockMvc mockMvc = mockMvcFor(presentService(service), new ConfigModelCatalog(List.of()));

        mockMvc.perform(get("/api/cv/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.models", hasSize(1)))
                .andExpect(jsonPath("$.models[0].id").value("orion12l.pt"))
                .andExpect(jsonPath("$.models[0].status").value("LIVE"))
                .andExpect(jsonPath("$.models[0].availability").value("MISSING"))
                .andExpect(jsonPath("$.models[0].source").value("registry"));
    }
}
