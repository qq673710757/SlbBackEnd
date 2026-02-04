package com.slb.mining_backend.modules.xmr.service.antpool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class AntpoolParser {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public ParsedWorkers parseWorkers(String body) {
        if (!StringUtils.hasText(body)) {
            return new ParsedWorkers(List.of(), 0);
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode data = root.path("data");
            JsonNode result = data.path("result");
            JsonNode rows = result.path("rows");
            if (!rows.isArray()) {
                rows = data.path("rows");
            }
            if (!rows.isArray()) {
                rows = root.path("rows");
            }
            List<WorkerItem> items = new ArrayList<>();
            if (rows.isArray()) {
                for (JsonNode row : rows) {
                    if (row == null || row.isNull()) {
                        continue;
                    }
                    String worker = text(row, "workerId");
                    if (!StringUtils.hasText(worker)) {
                        worker = text(row, "worker");
                    }
                    BigDecimal last10m = parseHashrateMhs(row.get("hsLast10min"));
                    if (last10m == null) {
                        last10m = parseHashrateMhs(row.get("hsLast1h"));
                    }
                    if (last10m == null) {
                        last10m = parseHashrateMhs(row.get("hsLast1hour"));
                    }
                    if (last10m == null) {
                        last10m = decimal(row, "last10m");
                    }
                    if (StringUtils.hasText(worker)) {
                        items.add(new WorkerItem(worker, last10m));
                    }
                }
            }
            JsonNode paging = result.isMissingNode() || result.isNull() ? data : result;
            int totalPages = parseTotalPages(paging);
            return new ParsedWorkers(items, totalPages);
        } catch (Exception ignored) {
            return new ParsedWorkers(List.of(), 0);
        }
    }

    public List<PayoutItem> parsePayouts(String body) {
        if (!StringUtils.hasText(body)) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode data = root.path("data");
            JsonNode rows = data.path("rows");
            if (!rows.isArray()) {
                rows = data.path("result").path("rows");
            }
            if (!rows.isArray()) {
                rows = root.path("rows");
            }
            List<PayoutItem> items = new ArrayList<>();
            if (rows.isArray()) {
                for (JsonNode row : rows) {
                    if (row == null || row.isNull()) {
                        continue;
                    }
                    String txId = text(row, "txId");
                    String timestamp = text(row, "timestamp");
                    BigDecimal amount = decimal(row, "amount");
                    items.add(new PayoutItem(txId, timestamp, amount));
                }
            }
            return items;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    public ParsedAccountBalance parseAccountBalance(String body) {
        if (!StringUtils.hasText(body)) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode data = root.path("data");
            if (data == null || data.isMissingNode() || data.isNull()) {
                return null;
            }
            return new ParsedAccountBalance(
                    decimal(data, "earn24Hours"),
                    decimal(data, "earnTotal"),
                    decimal(data, "paidOut"),
                    decimal(data, "balance"),
                    text(data, "settleTime")
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    private int parseTotalPages(JsonNode data) {
        if (data == null || data.isMissingNode()) {
            return 0;
        }
        int totalPages = intValue(data, "pageCount");
        if (totalPages <= 0) {
            totalPages = intValue(data, "totalPage");
        }
        if (totalPages <= 0) {
            totalPages = intValue(data, "totalPages");
        }
        if (totalPages <= 0) {
            int total = intValue(data, "total");
            int pageSize = intValue(data, "pageSize");
            if (total > 0 && pageSize > 0) {
                totalPages = (total + pageSize - 1) / pageSize;
            }
        }
        return totalPages;
    }

    private BigDecimal parseHashrateMhs(JsonNode node) {
        return parseHashrateMhs(textValue(node));
    }

    private BigDecimal parseHashrateMhs(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
        Matcher matcher = Pattern.compile("^([0-9]+(?:\\.[0-9]+)?)([A-Z]+/S)?$").matcher(normalized);
        if (!matcher.matches()) {
            return null;
        }
        BigDecimal value;
        try {
            value = new BigDecimal(matcher.group(1));
        } catch (NumberFormatException ex) {
            return null;
        }
        String unit = matcher.group(2);
        if (!StringUtils.hasText(unit) || "MH/S".equals(unit)) {
            return value;
        }
        BigDecimal thousand = BigDecimal.valueOf(1_000L);
        BigDecimal million = BigDecimal.valueOf(1_000_000L);
        return switch (unit) {
            case "H/S" -> value.divide(million, 12, RoundingMode.HALF_UP);
            case "KH/S" -> value.divide(thousand, 12, RoundingMode.HALF_UP);
            case "GH/S" -> value.multiply(thousand);
            case "TH/S" -> value.multiply(million);
            case "PH/S" -> value.multiply(BigDecimal.valueOf(1_000_000_000L));
            case "EH/S" -> value.multiply(BigDecimal.valueOf(1_000_000_000_000L));
            default -> value;
        };
    }

    private String textValue(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isTextual() || node.isNumber()) {
            return node.asText();
        }
        return null;
    }

    private String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode()) {
            return null;
        }
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isTextual()) {
            return v.asText();
        }
        if (v.isNumber()) {
            return v.asText();
        }
        return null;
    }

    private int intValue(JsonNode node, String field) {
        if (node == null || node.isMissingNode()) {
            return 0;
        }
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return 0;
        }
        return v.asInt(0);
    }

    private BigDecimal decimal(JsonNode node, String field) {
        String value = text(node, field);
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    public record WorkerItem(String workerId, BigDecimal last10m) {
    }

    public record ParsedWorkers(List<WorkerItem> items, int totalPages) {
    }

    public record PayoutItem(String txId, String timestamp, BigDecimal amount) {
    }

    public record ParsedAccountBalance(BigDecimal earn24Hours,
                                       BigDecimal earnTotal,
                                       BigDecimal paidOut,
                                       BigDecimal balance,
                                       String settleTime) {
    }
}
