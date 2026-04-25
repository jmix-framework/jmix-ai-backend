package io.jmix.ai.backend.retrieval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jmix.ai.backend.vectorstore.VectorStoreRepository;
import io.jmix.ai.backend.vectorstore.business.BusinessDocumentsSupport;
import io.jmix.ai.backend.entity.VectorStoreEntity;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class BusinessMetricsService {

    private static final Pattern ORDER_CLIENT_PATTERN = Pattern.compile("^Клиент:\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern ORDER_NUMBER_PATTERN = Pattern.compile("^Номер заказа:\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern ORDER_AMOUNT_PATTERN = Pattern.compile("^Итоговая сумма заказа:\\s*(\\d+(?:[.,]\\d+)?)\\s*$", Pattern.MULTILINE);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final VectorStoreRepository vectorStoreRepository;

    public BusinessMetricsService(VectorStoreRepository vectorStoreRepository) {
        this.vectorStoreRepository = vectorStoreRepository;
    }

    public BusinessMetricsResult analyze(String queryText) {
        if (!isMetricsQuery(queryText)) {
            return null;
        }

        List<OrderRecord> orders = loadOrders();
        if (orders.isEmpty()) {
            return null;
        }

        String normalizedQuery = normalize(queryText);
        List<OrderRecord> matched = filterByClient(queryText, orders);
        if (matched.isEmpty()) {
            if (isSpecificClientQuery(normalizedQuery)) {
                boolean russian = containsCyrillic(queryText);
                String noDataMessage = russian
                        ? "В загруженных бизнес-документах не найдено заказов для указанного клиента."
                        : "No orders were found for the specified client in the uploaded business documents.";
                return new BusinessMetricsResult(noDataMessage, List.of());
            }
            matched = orders;
        }

        Map<String, List<OrderRecord>> byClient = groupByClient(matched);
        String answer = buildAnswer(queryText, byClient);
        if (StringUtils.isBlank(answer)) {
            return null;
        }

        List<Document> documents = matched.stream()
                .map(order -> new Document(
                        order.documentPath(),
                        order.rawText(),
                        order.metadata()))
                .toList();

        return new BusinessMetricsResult(answer, documents);
    }

    private List<OrderRecord> loadOrders() {
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        var filter = b.eq("type", "business-documents").build();

        List<VectorStoreEntity> entities = vectorStoreRepository.loadList(filter, 0, 0);

        // Дедупликация по documentPath — один файл может быть разбит на чанки,
        // для парсинга суммы берём первый чанк (поля заказа в начале файла)
        Map<String, VectorStoreEntity> byPath = new LinkedHashMap<>();
        for (VectorStoreEntity entity : entities) {
            Map<String, Object> meta = parseMetadata(entity.getMetadata());
            String path = firstNonBlank(
                    meta.get("documentPath"),
                    meta.get("docPath"),
                    meta.get("source")
            );
            if (path != null) {
                byPath.putIfAbsent(path, entity);
            }
        }

        return byPath.values().stream()
                .map(entity -> parseOrderEntity(entity, parseMetadata(entity.getMetadata())))
                .flatMap(Optional::stream)
                .sorted(Comparator.comparing(OrderRecord::documentPath))
                .toList();
    }

    private Optional<OrderRecord> parseOrderEntity(VectorStoreEntity entity, Map<String, Object> metadata) {
        if (!isOrderMetadata(metadata)) {
            return Optional.empty();
        }
        String content = entity.getContent();
        if (content == null) {
            return Optional.empty();
        }
        Matcher clientMatcher = ORDER_CLIENT_PATTERN.matcher(content);
        Matcher numberMatcher = ORDER_NUMBER_PATTERN.matcher(content);
        Matcher amountMatcher = ORDER_AMOUNT_PATTERN.matcher(content);
        if (!clientMatcher.find() || !numberMatcher.find() || !amountMatcher.find()) {
            return Optional.empty();
        }
        String clientName = clientMatcher.group(1).trim();
        String orderNumber = numberMatcher.group(1).trim();
        BigDecimal amount = new BigDecimal(amountMatcher.group(1).replace(',', '.'));
        String path = firstNonBlank(
                metadata.get("documentPath"),
                metadata.get("docPath"),
                metadata.get("source")
        );
        if (path == null) {
            path = orderNumber;
        }
        return Optional.of(new OrderRecord(clientName, orderNumber, amount, path, content, metadata));
    }

    private List<OrderRecord> filterByClient(String queryText, List<OrderRecord> orders) {
        String normalizedQuery = normalize(queryText);
        return orders.stream()
                .filter(o -> normalizedQuery.contains(normalize(o.clientName())))
                .toList();
    }

    private Map<String, List<OrderRecord>> groupByClient(List<OrderRecord> orders) {
        Map<String, List<OrderRecord>> result = new LinkedHashMap<>();
        for (OrderRecord order : orders) {
            result.computeIfAbsent(order.clientName(), k -> new ArrayList<>()).add(order);
        }
        return result;
    }

    private String buildAnswer(String queryText, Map<String, List<OrderRecord>> byClient) {
        String normalizedQuery = normalize(queryText);
        boolean russian = containsCyrillic(queryText);

        if (isLargestOrderQuery(normalizedQuery)) {
            if (byClient.size() != 1) {
                return russian
                        ? "Нужен конкретный клиент. Уточните, по какому клиенту найти самый крупный заказ."
                        : "A specific client is required. Clarify which client you want the largest order for.";
            }
            Map.Entry<String, List<OrderRecord>> entry = byClient.entrySet().iterator().next();
            OrderRecord max = entry.getValue().stream()
                    .max(Comparator.comparing(OrderRecord::amount))
                    .orElse(null);
            if (max == null) return null;
            return russian
                    ? "Самый крупный заказ клиента %s: %s, сумма %s RUB.".formatted(entry.getKey(), max.orderNumber(), formatAmount(max.amount()))
                    : "The largest order for %s is %s with amount %s RUB.".formatted(entry.getKey(), max.orderNumber(), formatAmount(max.amount()));
        }

        if (isAverageQuery(normalizedQuery)) {
            if (byClient.size() == 1) {
                Map.Entry<String, List<OrderRecord>> entry = byClient.entrySet().iterator().next();
                return russian
                        ? "Средняя сумма заказа клиента %s: %s RUB.".formatted(entry.getKey(), formatAmount(average(entry.getValue())))
                        : "Average order amount for %s: %s RUB.".formatted(entry.getKey(), formatAmount(average(entry.getValue())));
            }
            return buildMultiClientOverview(byClient, russian, true, false);
        }

        if (isCountQuery(normalizedQuery) && !containsTotalIntent(normalizedQuery)) {
            if (byClient.size() == 1) {
                Map.Entry<String, List<OrderRecord>> entry = byClient.entrySet().iterator().next();
                return russian
                        ? "У клиента %s %d заказ(а/ов).".formatted(entry.getKey(), entry.getValue().size())
                        : "%s has %d order(s).".formatted(entry.getKey(), entry.getValue().size());
            }
            return buildMultiClientOverview(byClient, russian, false, true);
        }

        if (containsTotalIntent(normalizedQuery)) {
            if (byClient.size() == 1) {
                Map.Entry<String, List<OrderRecord>> entry = byClient.entrySet().iterator().next();
                return russian
                        ? "Общая сумма заказов клиента %s: %s RUB.".formatted(entry.getKey(), formatAmount(total(entry.getValue())))
                        : "Total order amount for %s: %s RUB.".formatted(entry.getKey(), formatAmount(total(entry.getValue())));
            }
            return buildMultiClientOverview(byClient, russian, true, isCountQuery(normalizedQuery));
        }

        return buildMultiClientOverview(byClient, russian, true, true);
    }

    private String buildMultiClientOverview(Map<String, List<OrderRecord>> byClient, boolean russian,
                                            boolean includeTotals, boolean includeCounts) {
        BigDecimal grandTotal = byClient.values().stream()
                .flatMap(List::stream).map(OrderRecord::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        int grandCount = byClient.values().stream().mapToInt(List::size).sum();

        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, List<OrderRecord>> entry : byClient.entrySet()) {
            List<String> parts = new ArrayList<>();
            if (includeCounts) parts.add(russian ? "%d заказ(а/ов)".formatted(entry.getValue().size()) : "%d order(s)".formatted(entry.getValue().size()));
            if (includeTotals) parts.add("%s RUB".formatted(formatAmount(total(entry.getValue()))));
            lines.add("%s: %s.".formatted(entry.getKey(), String.join(", ", parts)));
        }
        if (includeTotals && byClient.size() > 1) {
            lines.add(russian ? "Итого по всем клиентам: %s RUB.".formatted(formatAmount(grandTotal))
                    : "Grand total across all clients: %s RUB.".formatted(formatAmount(grandTotal)));
        }
        if (includeCounts && byClient.size() > 1) {
            lines.add(russian ? "Всего заказов: %d.".formatted(grandCount) : "Total orders: %d.".formatted(grandCount));
        }
        return String.join("\n", lines);
    }

    private boolean isMetricsQuery(String queryText) {
        String normalized = normalize(queryText);
        return containsTotalIntent(normalized) || isCountQuery(normalized)
                || isAverageQuery(normalized) || isLargestOrderQuery(normalized)
                || isOrderDomainQuery(normalized);
    }

    private boolean containsTotalIntent(String n) {
        return n.contains("сумм") || n.contains("итог") || n.contains("total") || n.contains("amount");
    }

    private boolean isCountQuery(String n) {
        return n.contains("сколько") || n.contains("колич") || n.contains("count");
    }

    private boolean isAverageQuery(String n) {
        return n.contains("средн") || n.contains("average");
    }

    private boolean isLargestOrderQuery(String n) {
        return n.contains("крупн") || n.contains("максим") || n.contains("largest") || n.contains("biggest");
    }

    private boolean isOrderDomainQuery(String n) {
        return n.contains("заказ") || n.contains("order");
    }

    private boolean isSpecificClientQuery(String normalizedQuery) {
        return normalizedQuery.contains("у клиента ")
                || normalizedQuery.contains("для клиента ")
                || normalizedQuery.contains("клиента ")
                || normalizedQuery.contains("client ");
    }

    private static BigDecimal total(List<OrderRecord> orders) {
        return orders.stream().map(OrderRecord::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal average(List<OrderRecord> orders) {
        if (orders.isEmpty()) return BigDecimal.ZERO;
        return total(orders).divide(BigDecimal.valueOf(orders.size()), 2, RoundingMode.HALF_UP);
    }

    private static String normalize(String value) {
        String s = value == null ? "" : value.toLowerCase(Locale.ROOT);
        s = s.replace("ё", "е").replace("ооо", "").replace("\"", "").replace("«", "").replace("»", "");
        s = s.replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}]+", " ");
        return s.trim().replaceAll("\\s+", " ");
    }

    private static boolean containsCyrillic(String value) {
        return value != null && value.codePoints().anyMatch(cp -> Character.UnicodeBlock.of(cp) == Character.UnicodeBlock.CYRILLIC);
    }

    private static String formatAmount(BigDecimal value) {
        BigDecimal n = value.stripTrailingZeros();
        if (n.scale() < 0) n = n.setScale(0, RoundingMode.UNNECESSARY);
        return n.toPlainString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseMetadata(String metadataJson) {
        if (metadataJson == null) return Map.of();
        try {
            return OBJECT_MAPPER.readValue(metadataJson, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static boolean isOrderMetadata(Map<String, Object> metadata) {
        String documentKind = asString(metadata.get("documentKind"));
        if ("order".equals(documentKind)) {
            return true;
        }

        String path = firstNonBlank(
                metadata.get("documentPath"),
                metadata.get("docPath"),
                metadata.get("source")
        );
        return path != null && path.replace('\\', '/').startsWith("orders/");
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private static String firstNonBlank(Object... values) {
        for (Object value : values) {
            String text = asString(value);
            if (StringUtils.isNotBlank(text)) {
                return text;
            }
        }
        return null;
    }

    public record BusinessMetricsResult(String answer, List<Document> supportingDocuments) {
    }

    private record OrderRecord(String clientName,
                               String orderNumber,
                               BigDecimal amount,
                               String documentPath,
                               String rawText,
                               Map<String, Object> metadata) {
    }
}
