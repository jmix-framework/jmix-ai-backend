package io.jmix.ai.backend.chat;

import org.apache.commons.lang3.StringUtils;
import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

final class ChatQueryClassifier {

    private static final Pattern TECHNICAL_PROMPT_PATTERN = Pattern.compile(
            "(?iu)\\b(" +
                    "jmix|cuba|vaadin|java|kotlin|groovy|spring|xml|sql|jpql|rest|api|dto|entity|entities|screen|screens|view|views|" +
                    "controller|service|repository|fetch\\s*plan|datamanager|entitymanager|liquibase|migration|role|security|permission|" +
                    "query|database|db|table|column|annotation|bean|gradle|docker|yaml|json|code|bug|error|exception|stacktrace|" +
                    "validation|validator|required|mandatory|constraint|one\\s*to\\s*many|onetomany|many\\s*to\\s*one|manytoone|sum|total|" +
                    "button|click|listener|notification|example|sample|snippet|code\\s*example|" +
                    "экран\\w*|сущност\\w*|контроллер\\w*|сервис\\w*|репозитор\\w*|запрос\\w*|баз\\w*|данн\\w*|таблиц\\w*|колонк\\w*|аннотац\\w*|роль\\w*|безопасност\\w*|" +
                    "ошиб\\w*|исключен\\w*|код\\w*|конфиг\\w*|миграц\\w*|ликвибейз\\w*|датаменеджер\\w*|валидац\\w*|валидатор\\w*|обязательн\\w*|сумм\\w*|" +
                    "атрибут\\w*|поле\\w*|связ\\w*|one\\s*tomany|кнопк\\w*|слушател\\w*|уведомлен\\w*|пример\\w*|образец\\w*" +
                    ")\\b"
    );
    private static final Pattern UI_PROMPT_PATTERN = Pattern.compile(
            "(?iu)\\b(" +
                    "ui|screen|screens|layout|dialog|datatable|datagrid|grid|table|form|field|button|combobox|lookup|editor|browse|" +
                    "fragment|xml|datasource|dataloader|data loader|tableactions|actions|component|components|event|events|notification|" +
                    "экран|экраны|экране|форма|формы|диалог|кнопк\\w*|таблиц|грид|компонент|компоненты|лейаут|событи|фрагмент|уведомлен\\w*|слушател\\w*" +
                    ")\\b"
    );
    private static final Pattern FRAMEWORK_PROMPT_PATTERN = Pattern.compile(
            "(?iu)\\b(" +
                    "source|sources|implementation|internal|internals|under the hood|how it works|framework|stacktrace|" +
                    "exception|error|bug|issue|debug|trace|реализац|исходник|исходники|внутрен|ошибк|исключен|фреймворк|трассировк|дебаг" +
                    ")\\b"
    );
    private static final Pattern CONCEPTUAL_PROMPT_PATTERN = Pattern.compile(
            "(?iu)\\b(" +
                    "difference|compare|comparison|when to use|when should|recommended|recommend|which approach|best practice|vs|" +
                    "разниц|сравн|в каких случаях|когда использовать|что рекомендуется|какой подход|что лучше|когда лучше|рекомендуется использовать" +
                    ")\\b"
    );
    private static final Pattern EXAMPLE_INTENT_PATTERN = Pattern.compile(
            "(?iu)\\b(" +
                    "example|sample|snippet|show|give|provide|how to|how do i|implement|" +
                    "пример|покажи|дай|предостав|как сделать|как реализовать|как настроить|как открыть" +
                    ")\\b"
    );
    private static final Pattern STUDIO_WORKFLOW_PROMPT_PATTERN = Pattern.compile(
            "(?iu)\\b(" +
                    "studio|designer|wizard|scaffold|generate|generation|jpa\\s*entity|entity\\s*designer|create\\s*screen|" +
                    "browse/edit|standard\\s*editor|standard\\s*lookup|liquibase\\s*changelog|" +
                    "студио|мастер|дизайнер|сгенерир|генерир|через studio|entity\\s*designer|создать\\s+сущност|создать\\s+экран|" +
                    "browse/edit|liquibase\\s*changelog|ликвибейс\\s*changelog" +
                    ")\\b"
    );
    private static final Pattern SECURITY_PROMPT_PATTERN = Pattern.compile(
            "(?iu)\\b(" +
                    "security|role|roles|policy|policies|permission|permissions|access|owner|creator|row\\s*level|resource\\s*role|" +
                    "entity\\s*policy|attribute\\s*policy|view\\s*policy|menu\\s*policy|" +
                    "безопасност\\w*|роль\\w*|политик\\w*|разрешени\\w*|доступ\\w*|создател\\w*|владел\\w*|строчн\\w*|ресурсн\\w*|редактирован\\w*|менеджер\\w*" +
                    ")\\b"
    );
    private static final Pattern DATA_ACCESS_PROMPT_PATTERN = Pattern.compile(
            "(?iu)\\b(" +
                    "datamanager|entitymanager|fetch\\s*plan|screenbuilders|standardeditor|standardlookup|one\\s*to\\s*many|many\\s*to\\s*one|sum|total|" +
                    "датаменеджер|entitymanager|fetch\\s*plan|screenbuilders|standardeditor|standardlookup|связ\\w*|сумм\\w*|агрег\\w*" +
                    ")\\b"
    );
    private static final Pattern DELEGATE_API_PROMPT_PATTERN = Pattern.compile(
            "(?iu)(" +
                    "@install|@subscribe|@supply|" +
                    "load\\s*delegate|save\\s*delegate|remove\\s*delegate|total\\s*count\\s*delegate|" +
                    "option\\s*caption\\s*provider|value\\s*provider|formatter|validator|subject\\s*=|target\\s*=|" +
                    "beforeactionperformedhandler|aftersavehandler|clicklistener|" +
                    "делегат|делегаты|обработчик|обработчики|валидатор|форматтер" +
                    ")"
    );
    private static final Pattern EXACT_API_SYMBOL_PROMPT_PATTERN = Pattern.compile(
            "(?iu)(" +
                    "@[a-z_][a-z0-9_]*|" +
                    "\\b[a-z][a-z0-9]*?(delegate|provider|handler|listener|formatter|validator)\\b|" +
                    "\\b(beforeActionPerformedHandler|afterSaveHandler|loadDelegate|saveDelegate|removeDelegate|totalCountDelegate|optionCaptionProvider|valueProvider)\\b" +
                    ")"
    );

    boolean isTechnicalPrompt(@Nullable String userPrompt) {
        if (StringUtils.isBlank(userPrompt)) {
            return false;
        }

        String normalized = userPrompt.trim();
        String lowercase = normalized.toLowerCase();
        return TECHNICAL_PROMPT_PATTERN.matcher(normalized).find()
                || isUiPrompt(normalized)
                || isFrameworkPrompt(normalized)
                || isStudioWorkflowPrompt(normalized)
                || isExactApiSymbolPrompt(normalized)
                || isConceptualPrompt(normalized)
                || EXAMPLE_INTENT_PATTERN.matcher(normalized).find()
                || containsAny(lowercase,
                "дай пример", "покажи пример", "покажи код", "пример", "код", "xml", "java", "button", "click",
                "listener", "notification", "кнопк", "клик", "слушател", "уведомлен",
                "какой подход", "что рекомендуется", "в каких случаях", "ограничить редактирование",
                "должен видеть все", "производительности экрана", "через studio",
                "one-to-many", "onetomany", "one to many", "обязательным", "валидацию",
                "общей суммой заказа", "spring bean", "спринг бин", "сервис", "сущности")
                || normalized.contains("\n")
                || normalized.contains("```")
                || normalized.contains("@")
                || normalized.contains("<")
                || normalized.contains(">");
    }

    RetrievalPlan buildRetrievalPlan(@Nullable String userPrompt) {
        List<String> toolNames = new ArrayList<>();
        toolNames.add("documentation_retriever");

        if (isFrameworkPrompt(userPrompt) || isExactApiSymbolPrompt(userPrompt)) {
            toolNames.add("framework_retriever");
        }
        if (isConceptualPrompt(userPrompt) || isSecurityPrompt(userPrompt) || isDataAccessPrompt(userPrompt)) {
            addIfMissing(toolNames, "framework_retriever");
        }
        if (isStudioWorkflowPrompt(userPrompt)) {
            addIfMissing(toolNames, "trainings_retriever");
        }
        if (isUiPrompt(userPrompt) || isExampleIntentPrompt(userPrompt)) {
            addIfMissing(toolNames, "uisamples_retriever");
        }

        return new RetrievalPlan(List.copyOf(toolNames));
    }

    boolean isUiPrompt(@Nullable String userPrompt) {
        if (StringUtils.isBlank(userPrompt)) {
            return false;
        }
        return UI_PROMPT_PATTERN.matcher(userPrompt).find()
                || DELEGATE_API_PROMPT_PATTERN.matcher(userPrompt).find()
                || containsAny(userPrompt.toLowerCase(),
                "screen", "layout", "dialog", "datatable", "datagrid", "datagridloader", "grid", "lookup",
                "editor", "browse", "fragment", "component", "components", "event", "events", "button", "click",
                "notification", "listener",
                "экран", "экраны", "экране", "диалог", "грид", "компонент", "компоненты", "событи", "кнопк",
                "уведомлен", "слушател", "@install", "@subscribe", "@supply", "loaddelegate", "validator",
                "formatter", "optioncaptionprovider", "valueprovider");
    }

    boolean isFrameworkPrompt(@Nullable String userPrompt) {
        if (StringUtils.isBlank(userPrompt)) {
            return false;
        }
        if (isConceptualPrompt(userPrompt)) {
            return false;
        }
        String normalized = userPrompt.toLowerCase();
        return FRAMEWORK_PROMPT_PATTERN.matcher(userPrompt).find()
                || containsAny(normalized,
                "framework", "source", "implementation", "internal", "how it works", "under the hood",
                "stacktrace", "exception", "error", "bug", "issue", "debug", "trace",
                "фреймворк", "исходник", "исходники", "реализац", "внутрен", "как устроен",
                "как работает внутри", "ошибк", "исключен", "дебаг", "трассировк");
    }

    boolean isExampleIntentPrompt(@Nullable String userPrompt) {
        return StringUtils.isNotBlank(userPrompt) && EXAMPLE_INTENT_PATTERN.matcher(userPrompt).find();
    }

    boolean isStudioWorkflowPrompt(@Nullable String userPrompt) {
        return StringUtils.isNotBlank(userPrompt)
                && STUDIO_WORKFLOW_PROMPT_PATTERN.matcher(userPrompt).find();
    }

    boolean isExactApiSymbolPrompt(@Nullable String userPrompt) {
        return StringUtils.isNotBlank(userPrompt)
                && EXACT_API_SYMBOL_PROMPT_PATTERN.matcher(userPrompt).find();
    }

    boolean isSecurityPrompt(@Nullable String userPrompt) {
        return StringUtils.isNotBlank(userPrompt)
                && (SECURITY_PROMPT_PATTERN.matcher(userPrompt).find()
                || containsAny(userPrompt.toLowerCase(),
                "ограничить редактирование", "должен видеть все", "только их создателю",
                "only their creator", "manager should see all"));
    }

    boolean isDataAccessPrompt(@Nullable String userPrompt) {
        return StringUtils.isNotBlank(userPrompt)
                && DATA_ACCESS_PROMPT_PATTERN.matcher(userPrompt).find();
    }

    private boolean isConceptualPrompt(@Nullable String userPrompt) {
        return StringUtils.isNotBlank(userPrompt) && CONCEPTUAL_PROMPT_PATTERN.matcher(userPrompt).find();
    }

    record RetrievalPlan(List<String> toolNames) {
    }

    private static boolean containsAny(String normalizedPrompt, String... needles) {
        for (String needle : needles) {
            if (normalizedPrompt.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static void addIfMissing(List<String> toolNames, String toolName) {
        if (!toolNames.contains(toolName)) {
            toolNames.add(toolName);
        }
    }
}
