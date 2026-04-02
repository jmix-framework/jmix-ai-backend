package io.jmix.ai.backend.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.jmix.ai.backend.parameters.ParametersReader;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.lang.reflect.Method;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChatImplTest {

    private final ObjectMapper objectMapper = new ObjectMapper(new YAMLFactory());

    @Test
    void nonTechnicalPromptDisablesToolsByDefault() throws Exception {
        ParametersReader parametersReader = new ParametersReader(readYaml("""
                model:
                  name: test-model
                tools:
                  skipForTrivialPrompts: true
                """));

        assertThat(ChatImpl.shouldEnableTools("Привет, чат работает?", parametersReader)).isFalse();
    }

    @Test
    void technicalPromptKeepsToolsEnabled() throws Exception {
        ParametersReader parametersReader = new ParametersReader(readYaml("""
                model:
                  name: test-model
                tools:
                  skipForTrivialPrompts: true
                """));

        assertThat(ChatImpl.shouldEnableTools("Как в Jmix 1.7 открыть Screen через ScreenBuilders?", parametersReader)).isTrue();
    }

    @Test
    void configCanForceToolsForNonTechnicalPrompts() throws Exception {
        ParametersReader parametersReader = new ParametersReader(readYaml("""
                model:
                  name: test-model
                tools:
                  skipForTrivialPrompts: false
                """));

        assertThat(ChatImpl.shouldEnableTools("Привет, чат работает?", parametersReader)).isTrue();
    }

    @Test
    void technicalPromptDetectionIncludesFrameworkSourceQuestions() throws Exception {
        ParametersReader parametersReader = new ParametersReader(readYaml("""
                model:
                  name: test-model
                tools:
                  skipForTrivialPrompts: true
                """));

        assertThat(ChatImpl.shouldEnableTools("Покажи, как это реализовано в исходниках Jmix DataManager", parametersReader)).isTrue();
    }

    @Test
    void uiExamplePromptKeepsToolsEnabled() throws Exception {
        ParametersReader parametersReader = new ParametersReader(readYaml("""
                model:
                  name: test-model
                tools:
                  skipForTrivialPrompts: true
                """));

        assertThat(ChatImpl.shouldEnableTools("Дай пример кнопки со слушателем клика, по клику должно быть показано уведомление", parametersReader))
                .isTrue();
    }

    @Test
    void uiConfirmationPromptKeepsToolsEnabled() throws Exception {
        ParametersReader parametersReader = new ParametersReader(readYaml("""
                model:
                  name: test-model
                tools:
                  skipForTrivialPrompts: true
                """));

        assertThat(ChatImpl.shouldEnableTools("Как реализовать диалог подтверждения перед удалением записи из таблицы?", parametersReader))
                .isTrue();
    }

    @Test
    void performanceScenarioPromptKeepsToolsEnabled() throws Exception {
        ParametersReader parametersReader = new ParametersReader(readYaml("""
                model:
                  name: test-model
                tools:
                  skipForTrivialPrompts: true
                """));

        assertThat(ChatImpl.shouldEnableTools("Приложение работает медленно при загрузке списка заказов. Какие шаги предпринять для оптимизации производительности экрана?", parametersReader))
                .isTrue();
    }

    @Test
    void conceptualAccessControlPromptKeepsToolsEnabled() throws Exception {
        ParametersReader parametersReader = new ParametersReader(readYaml("""
                model:
                  name: test-model
                tools:
                  skipForTrivialPrompts: true
                """));

        assertThat(ChatImpl.shouldEnableTools("Какой подход выбрать, если нужно ограничить редактирование заказов только их создателю, но при этом менеджер должен видеть все заказы?", parametersReader))
                .isTrue();
    }

    @Test
    void studioWorkflowPromptKeepsToolsEnabled() throws Exception {
        ParametersReader parametersReader = new ParametersReader(readYaml("""
                model:
                  name: test-model
                tools:
                  skipForTrivialPrompts: true
                """));

        assertThat(ChatImpl.shouldEnableTools("Как создать новую сущность (entity) в проекте Jmix 1.7 через Studio? Опиши шаги.", parametersReader))
                .isTrue();
    }

    @Test
    void entityValidationPromptKeepsToolsEnabled() throws Exception {
        ParametersReader parametersReader = new ParametersReader(readYaml("""
                model:
                  name: test-model
                tools:
                  skipForTrivialPrompts: true
                """));

        assertThat(ChatImpl.shouldEnableTools("Как настроить валидацию на уровне сущности, чтобы поле email было обязательным и имело формат email?", parametersReader))
                .isTrue();
    }

    @Test
    void aggregationScenarioPromptKeepsToolsEnabled() throws Exception {
        ParametersReader parametersReader = new ParametersReader(readYaml("""
                model:
                  name: test-model
                tools:
                  skipForTrivialPrompts: true
                """));

        assertThat(ChatImpl.shouldEnableTools("У меня есть сущности Заказ (Order) и Товар (Product). Заказ может содержать несколько товаров (связь OneToMany). Мне нужно в таблице заказов отобразить колонку с общей суммой заказа. Как это лучше реализовать?", parametersReader))
                .isTrue();
    }

    @Test
    void serviceInjectionPromptKeepsToolsEnabled() throws Exception {
        ParametersReader parametersReader = new ParametersReader(readYaml("""
                model:
                  name: test-model
                tools:
                  skipForTrivialPrompts: true
                """));

        assertThat(ChatImpl.shouldEnableTools("Как определить пользовательский сервис (Spring bean) и внедрить его в экран?", parametersReader))
                .isTrue();
    }

    @Test
    void retrievalPlanAlwaysStartsWithDocumentation() {
        assertThat(ChatImpl.buildRetrievalPlan("Как работает DataManager внутри framework Jmix 1.7?").toolNames())
                .containsExactly("documentation_retriever", "framework_retriever");
    }

    @Test
    void retrievalPlanAddsFrameworkForConceptualFactQuestions() {
        assertThat(ChatImpl.buildRetrievalPlan("Объясни разницу между EntityManager и DataManager в Jmix. В каких случаях что рекомендуется использовать?").toolNames())
                .containsExactly("documentation_retriever", "framework_retriever");
    }

    @Test
    void retrievalPlanAddsUiSamplesForUiQuestions() {
        assertThat(ChatImpl.buildRetrievalPlan("Как открыть lookup screen и настроить DataGrid в Jmix 1.7?").toolNames())
                .containsExactly("documentation_retriever", "uisamples_retriever");
    }

    @Test
    void retrievalPlanAddsUiSamplesForUiExamplePrompt() {
        assertThat(ChatImpl.buildRetrievalPlan("Дай пример кнопки со слушателем клика, по клику должно быть показано уведомление").toolNames())
                .containsExactly("documentation_retriever", "uisamples_retriever");
    }

    @Test
    void retrievalPlanAddsUiSamplesForDelegateAnnotationQuestions() {
        assertThat(ChatImpl.buildRetrievalPlan("Для чего используется аннотация @Install в Jmix?").toolNames())
                .containsExactly("documentation_retriever", "framework_retriever", "uisamples_retriever");
    }

    @Test
    void retrievalPlanAddsTrainingsForStudioWorkflowQuestions() {
        assertThat(ChatImpl.buildRetrievalPlan("Как создать новую сущность (entity) в проекте Jmix 1.7 через Studio? Опиши шаги.").toolNames())
                .containsExactly("documentation_retriever", "trainings_retriever");
    }

    @Test
    void retrievalPlanAddsTrainingsAndUiSamplesForStudioScreenGenerationQuestions() {
        assertThat(ChatImpl.buildRetrievalPlan("Как через Studio создать browse/edit screen для сущности Customer?").toolNames())
                .containsExactly("documentation_retriever", "trainings_retriever", "uisamples_retriever");
    }

    @Test
    void retrievalPlanAddsFrameworkForSecurityScenarioQuestions() {
        assertThat(ChatImpl.buildRetrievalPlan("Какой подход выбрать, если нужно ограничить редактирование заказов только их создателю, но при этом менеджер должен видеть все заказы?").toolNames())
                .containsExactly("documentation_retriever", "framework_retriever");
    }

    @Test
    void retrievalPlanCombinesFrameworkAndUiWhenQuestionNeedsBoth() {
        assertThat(ChatImpl.buildRetrievalPlan("Как устроен DataGridLoader внутри framework и как его использовать на экране?").toolNames())
                .containsExactly("documentation_retriever", "framework_retriever", "uisamples_retriever");
    }

    @Test
    void buildPromptUsesConfiguredSystemPromptForGeneralQuestion() throws Exception {
        Prompt prompt = invokeBuildPrompt("Чем ты можешь мне помочь?", "LONG_SYSTEM_PROMPT", null);

        assertThat(prompt.getSystemMessage()).isInstanceOf(SystemMessage.class);
        assertThat(prompt.getSystemMessage().getText()).isEqualTo("LONG_SYSTEM_PROMPT");
    }

    @Test
    void technicalPromptKeepsConfiguredSystemPrompt() throws Exception {
        Prompt prompt = invokeBuildPrompt("Как открыть Screen через ScreenBuilders в Jmix 1.7?", "LONG_SYSTEM_PROMPT", null);

        assertThat(prompt.getSystemMessage()).isInstanceOf(SystemMessage.class);
        assertThat(prompt.getSystemMessage().getText()).isEqualTo("LONG_SYSTEM_PROMPT");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readYaml(String yaml) throws Exception {
        return objectMapper.readValue(yaml, Map.class);
    }

    private Prompt invokeBuildPrompt(String userPrompt, String systemPrompt, String prefetchedContext) throws Exception {
        Method method = ChatImpl.class.getDeclaredMethod("buildPrompt", String.class, String.class, String.class);
        method.setAccessible(true);
        return (Prompt) method.invoke(null, userPrompt, systemPrompt, prefetchedContext);
    }
}
