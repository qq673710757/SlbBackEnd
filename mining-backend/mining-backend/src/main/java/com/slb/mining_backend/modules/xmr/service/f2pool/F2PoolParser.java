package com.slb.mining_backend.modules.xmr.service.f2pool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.slb.mining_backend.modules.xmr.config.F2PoolProperties;
import com.slb.mining_backend.modules.xmr.domain.F2PoolWorkerSample;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.OptionalInt;

@Component
@Slf4j
public class F2PoolParser {

    private static final Map<String, BigDecimal> HASHRATE_UNITS;
    private static final ZoneId BJT = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    static {
        HASHRATE_UNITS = new LinkedHashMap<>();
        // 统一口径：MH/s
        HASHRATE_UNITS.put("TH/S", BigDecimal.valueOf(1_000_000L));
        HASHRATE_UNITS.put("GH/S", BigDecimal.valueOf(1_000L));
        HASHRATE_UNITS.put("MH/S", BigDecimal.ONE);
        HASHRATE_UNITS.put("KH/S", BigDecimal.valueOf(0.001d));
        HASHRATE_UNITS.put("H/S", BigDecimal.valueOf(0.000001d));
    }

    private final ObjectMapper objectMapper;
    private final F2PoolProperties properties;

    public F2PoolParser(ObjectMapper objectMapper, F2PoolProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public List<F2PoolWorkerSample> parseWorkers(String json, F2PoolProperties.Account account) {
        if (!StringUtils.hasText(json) || account == null) {
            return Collections.emptyList();
        }
        F2PoolProperties.Mapping mapping = v1Mapping();
        Object raw = readPath(json, mapping.getWorkersPath());
        if (raw == null) {
            return Collections.emptyList();
        }
        return parseWorkersRaw(raw, account);
    }

    public List<F2PoolWorkerSample> parseWorkersV2(String json, F2PoolProperties.Account account) {
        if (!StringUtils.hasText(json) || account == null) {
            return Collections.emptyList();
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode data = root.has("data") ? root.get("data") : root;
            JsonNode list = resolveWorkerListNode(data);
            if (list == null || !list.isArray()) {
                return Collections.emptyList();
            }
            List<F2PoolWorkerSample> workers = new ArrayList<>();
            for (JsonNode node : list) {
                F2PoolWorkerSample sample = parseWorkerV2Item(node, account);
                if (sample != null) {
                    workers.add(sample);
                }
            }
            return workers;
        } catch (Exception ex) {
            return Collections.emptyList();
        }
    }

    /**
     * F2Pool 账户总览接口里的 workers 字段解析（兼容 array 结构）。
     * 用于 v2 不支持币种时的兜底 worker 列表解析。
     */
    public List<F2PoolWorkerSample> parseWorkersFromAccountOverview(String json, F2PoolProperties.Account account) {
        if (!StringUtils.hasText(json) || account == null) {
            return Collections.emptyList();
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode workersNode = root.get("workers");
            if (workersNode == null || !workersNode.isArray()) {
                return Collections.emptyList();
            }
            List<F2PoolWorkerSample> workers = new ArrayList<>();
            for (JsonNode node : workersNode) {
                F2PoolWorkerSample sample = parseAccountOverviewWorker(node, account);
                if (sample != null) {
                    workers.add(sample);
                }
            }
            return workers;
        } catch (Exception ex) {
            return Collections.emptyList();
        }
    }

    public ParsedAccountOverview parseAccountOverview(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        F2PoolProperties.Mapping mapping = v1Mapping();
        DocumentContext ctx = JsonPath.parse(json);
        BigDecimal hashrate = readDecimal(ctx, mapping.getAccountHashrate());
        String unit = readString(ctx, mapping.getAccountHashrateUnit());
        BigDecimal hashrateMhs = toMhs(hashrate, unit);
        Integer workers = readInt(ctx, mapping.getAccountWorkers());
        Integer active = readInt(ctx, mapping.getAccountActiveWorkers());
        BigDecimal fixedValue = readDecimal(ctx, mapping.getAccountFixedValue());
        return new ParsedAccountOverview(hashrateMhs, workers, active, fixedValue);
    }

    public List<PayoutItem> parsePayoutHistory(String json) {
        if (!StringUtils.hasText(json)) {
            return Collections.emptyList();
        }
        F2PoolProperties.Mapping mapping = v1Mapping();
        Object raw = readPath(json, mapping.getPayoutArray());
        if (raw == null) {
            try {
                raw = JsonPath.parse(json).json();
            } catch (Exception ignored) {
                raw = null;
            }
        }
        List<PayoutItem> items = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object node : list) {
                parsePayoutNode(node).ifPresent(items::add);
            }
        } else if (raw instanceof Map<?, ?> map) {
            for (Object node : map.values()) {
                parsePayoutNode(node).ifPresent(items::add);
            }
        } else {
            parsePayoutNode(raw).ifPresent(items::add);
        }
        return items;
    }

    public Optional<String> parseV2ErrorMessage(String json) {
        if (!StringUtils.hasText(json)) {
            return Optional.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode codeNode = root.get("code");
            if (codeNode != null && codeNode.isNumber() && codeNode.asInt() != 0) {
                String msg = root.has("msg") ? root.get("msg").asText() : "F2Pool v2 error";
                return Optional.of(msg);
            }
        } catch (Exception ignored) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    public Optional<V2Error> detectV2Error(String json) {
        if (!StringUtils.hasText(json)) {
            return Optional.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode codeNode = root.get("code");
            JsonNode msgNode = root.get("msg");
            if (codeNode != null && codeNode.isNumber() && msgNode != null && msgNode.isTextual()) {
                int code = codeNode.asInt();
                if (code != 0) {
                    return Optional.of(new V2Error(code, msgNode.asText()));
                }
            }
        } catch (Exception ignored) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    public OptionalInt resolveWorkersCountV2(String json) {
        return resolveArraySize(json, true);
    }

    public OptionalInt resolveTransactionsCountV2(String json) {
        return resolveArraySize(json, false);
    }

    public List<PayoutItem> parsePayoutHistoryV2(String json) {
        if (!StringUtils.hasText(json)) {
            return Collections.emptyList();
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode data = root.has("data") ? root.get("data") : root;
            JsonNode transactions = resolveTransactionsNode(data);
            if (transactions == null || !transactions.isArray()) {
                return Collections.emptyList();
            }
            List<PayoutItem> items = new ArrayList<>();
            for (JsonNode tx : transactions) {
                PayoutItem item = parsePayoutV2Item(tx);
                if (item != null) {
                    items.add(item);
                }
            }
            return items;
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    public ParsedAssetsBalance parseAssetsBalanceV2(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode data = root.has("data") ? root.get("data") : root;
            JsonNode info = data.has("balance_info") ? data.get("balance_info") : data.get("balanceInfo");
            if (info == null || info.isMissingNode() || info.isNull()) {
                return null;
            }
            return new ParsedAssetsBalance(
                    readDecimal(info.get("balance")),
                    readDecimal(info.get("immature_balance")),
                    readDecimal(info.get("paid")),
                    readDecimal(info.get("total_income")),
                    readDecimal(info.get("yesterday_income")),
                    readDecimal(info.get("estimated_today_income"))
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    public BigDecimal parseValueLastDay(String json) {
        if (!StringUtils.hasText(json)) {
            return BigDecimal.ZERO;
        }
        F2PoolProperties.Mapping mapping = v1Mapping();
        DocumentContext ctx = JsonPath.parse(json);
        BigDecimal value = readDecimal(ctx, mapping.getValueLastDay());
        return value != null ? value : BigDecimal.ZERO;
    }

    private List<F2PoolWorkerSample> parseWorkersRaw(Object raw, F2PoolProperties.Account account) {
        List<F2PoolWorkerSample> workers = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object node : list) {
                if (node instanceof List<?> row) {
                    parseWorkerRow(row, account).ifPresent(workers::add);
                } else {
                    parseWorkerNode(node, null, account).ifPresent(workers::add);
                }
            }
        } else if (raw instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String fallback = entry.getKey() != null ? entry.getKey().toString() : null;
                parseWorkerNode(entry.getValue(), fallback, account).ifPresent(workers::add);
            }
        } else {
            parseWorkerNode(raw, null, account).ifPresent(workers::add);
        }
        return workers;
    }

    private JsonNode resolveWorkerListNode(JsonNode data) {
        if (data == null || data.isMissingNode()) {
            return null;
        }
        if (data.isArray()) {
            return data;
        }
        JsonNode list = data.get("list");
        if (list != null) {
            return list;
        }
        list = data.get("rows");
        if (list != null) {
            return list;
        }
        list = data.get("workers");
        if (list != null) {
            return list;
        }
        list = data.get("worker_list");
        if (list != null) {
            return list;
        }
        return null;
    }

    private OptionalInt resolveArraySize(String json, boolean workers) {
        if (!StringUtils.hasText(json)) {
            return OptionalInt.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode data = root.has("data") ? root.get("data") : root;
            JsonNode list = workers ? resolveWorkerListNode(data) : resolveTransactionsNode(data);
            if (list == null || !list.isArray()) {
                return OptionalInt.empty();
            }
            return OptionalInt.of(list.size());
        } catch (Exception ignored) {
            return OptionalInt.empty();
        }
    }

    private F2PoolWorkerSample parseWorkerV2Item(JsonNode node, F2PoolProperties.Account account) {
        if (node == null || node.isMissingNode()) {
            return null;
        }
        JsonNode hashRateInfo = node.get("hash_rate_info");
        String workerId = readString(node.get("worker_name"));
        if (!StringUtils.hasText(workerId)) {
            workerId = readString(node.get("worker_id"));
        }
        if (!StringUtils.hasText(workerId)) {
            workerId = readString(node.get("name"));
        }
        if (!StringUtils.hasText(workerId) && hashRateInfo != null) {
            workerId = readString(hashRateInfo.get("name"));
        }
        if (!StringUtils.hasText(workerId)) {
            return null;
        }
        BigDecimal hashrate = readDecimal(node.get("hashrate"));
        if (hashrate == null) {
            hashrate = readDecimal(node.get("hash_rate"));
        }
        if (hashrate == null) {
            hashrate = readDecimal(node.get("cur_hashrate"));
        }
        if (hashrate == null && hashRateInfo != null) {
            hashrate = readDecimal(hashRateInfo.get("hash_rate"));
        }
        String unit = readString(node.get("hashrate_unit"));
        if (!StringUtils.hasText(unit)) {
            unit = readString(node.get("hash_rate_unit"));
        }
        if (!StringUtils.hasText(unit)) {
            unit = readString(node.get("unit"));
        }
        if (!StringUtils.hasText(unit) && hashRateInfo != null) {
            // v2 的 hash_rate_info 通常不带单位，按 H/s 处理以便转换成 MH/s
            unit = "H/S";
        }
        BigDecimal hashrateMhs = toMhs(hashrate, unit);
        BigDecimal avgHashrate = readDecimal(node.get("hashrate_avg"));
        if (avgHashrate == null) {
            avgHashrate = readDecimal(node.get("avg_hashrate"));
        }
        if (avgHashrate == null && hashRateInfo != null) {
            avgHashrate = readDecimal(hashRateInfo.get("h1_hash_rate"));
            if (avgHashrate == null) {
                avgHashrate = readDecimal(hashRateInfo.get("h24_hash_rate"));
            }
        }
        BigDecimal avgMhs = toMhs(avgHashrate, unit);
        Instant lastShare = readInstant(node.get("last_share_time")).orElse(null);
        if (lastShare == null) {
            lastShare = readInstant(node.get("last_share")).orElse(null);
        }
        if (lastShare == null) {
            lastShare = readInstant(node.get("last_share_at")).orElse(null);
        }
        if (lastShare == null) {
            lastShare = Instant.now();
        }
        double hashNow = hashrateMhs != null ? hashrateMhs.doubleValue() : 0d;
        double hashAvg = avgMhs != null ? avgMhs.doubleValue() : hashNow;
        return new F2PoolWorkerSample(account.getName(), account.getCoin(), workerId, hashNow, hashAvg, lastShare);
    }

    private F2PoolWorkerSample parseAccountOverviewWorker(JsonNode node, F2PoolProperties.Account account) {
        if (node == null || node.isMissingNode()) {
            return null;
        }
        if (node.isObject()) {
            return parseWorkerV2Item(node, account);
        }
        if (!node.isArray()) {
            return null;
        }
        String workerId = readStringAtNode(node, 0);
        if (!StringUtils.hasText(workerId)) {
            return null;
        }
        BigDecimal hashNow = readDecimalAtNode(node, 1);
        BigDecimal hashAvg = readDecimalAtNode(node, 2);
        if (hashNow == null && hashAvg == null) {
            return null;
        }
        if (hashNow == null) {
            hashNow = hashAvg;
        }
        if (hashAvg == null) {
            hashAvg = hashNow;
        }
        String unit = resolveHashrateUnit(node);
        if (StringUtils.hasText(unit)) {
            hashNow = toMhs(hashNow, unit);
            hashAvg = toMhs(hashAvg, unit);
        }
        Instant lastShare = resolveLastShareFromRow(node);
        if (lastShare == null) {
            lastShare = Instant.now();
        }
        double hashNowVal = hashNow != null ? hashNow.doubleValue() : 0d;
        double hashAvgVal = hashAvg != null ? hashAvg.doubleValue() : hashNowVal;
        return new F2PoolWorkerSample(account.getName(), account.getCoin(), workerId, hashNowVal, hashAvgVal, lastShare);
    }

    private String resolveHashrateUnit(JsonNode row) {
        if (row == null || !row.isArray()) {
            return null;
        }
        for (JsonNode item : row) {
            if (item != null && item.isTextual()) {
                String raw = item.asText();
                if (!StringUtils.hasText(raw)) {
                    continue;
                }
                String normalized = raw.trim().toUpperCase(Locale.ROOT);
                if (HASHRATE_UNITS.containsKey(normalized)) {
                    return normalized;
                }
            }
        }
        return null;
    }

    private Instant resolveLastShareFromRow(JsonNode row) {
        if (row == null || !row.isArray()) {
            return null;
        }
        if (row.size() > 6) {
            Optional<Instant> parsed = readInstant(row.get(6));
            if (parsed.isPresent()) {
                return parsed.get();
            }
        }
        for (JsonNode item : row) {
            Optional<Instant> parsed = readInstant(item);
            if (parsed.isPresent()) {
                return parsed.get();
            }
        }
        return null;
    }

    private String readStringAtNode(JsonNode row, int index) {
        if (row == null || !row.isArray() || index < 0 || index >= row.size()) {
            return null;
        }
        JsonNode value = row.get(index);
        return value == null || value.isNull() ? null : value.asText();
    }

    private BigDecimal readDecimalAtNode(JsonNode row, int index) {
        if (row == null || !row.isArray() || index < 0 || index >= row.size()) {
            return null;
        }
        return readDecimal(row.get(index));
    }

    private Optional<F2PoolWorkerSample> parseWorkerNode(Object node, String fallbackWorkerId, F2PoolProperties.Account account) {
        if (node == null) {
            return Optional.empty();
        }
        try {
            F2PoolProperties.Mapping mapping = v1Mapping();
            DocumentContext ctx = JsonPath.parse(node);
            String workerId = readString(ctx, mapping.getWorkerId());
            if (!StringUtils.hasText(workerId)) {
                workerId = fallbackWorkerId;
            }
            if (!StringUtils.hasText(workerId)) {
                return Optional.empty();
            }
            BigDecimal hashrate = readDecimal(ctx, mapping.getWorkerHashrate());
            String unit = readString(ctx, mapping.getWorkerHashrateUnit());
            BigDecimal hashrateMhs = toMhs(hashrate, unit);
            Instant lastShare = readInstant(ctx, mapping.getWorkerLastShare()).orElse(Instant.now());
            double hashNow = hashrateMhs != null ? hashrateMhs.doubleValue() : 0d;
            double hashAvg = hashNow;
            return Optional.of(new F2PoolWorkerSample(account.getName(), account.getCoin(), workerId, hashNow, hashAvg, lastShare));
        } catch (Exception ex) {
            log.debug("Failed to parse F2Pool worker node: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    private Optional<F2PoolWorkerSample> parseWorkerRow(List<?> row, F2PoolProperties.Account account) {
        if (row == null || row.isEmpty()) {
            return Optional.empty();
        }
        F2PoolProperties.Mapping mapping = v1Mapping();
        Integer idIndex = mapping.getWorkerArrayIdIndex();
        Integer hashIndex = mapping.getWorkerArrayHashIndex();
        Integer unitIndex = mapping.getWorkerArrayUnitIndex();
        Integer lastShareIndex = mapping.getWorkerArrayLastShareIndex();

        String workerId = readStringAt(row, idIndex);
        if (!StringUtils.hasText(workerId)) {
            return Optional.empty();
        }
        BigDecimal hashrate = readDecimalAt(row, hashIndex);
        String unit = readStringAt(row, unitIndex);
        BigDecimal hashrateMhs = toMhs(hashrate, unit);
        Instant lastShare = readInstantAt(row, lastShareIndex).orElse(Instant.now());
        double hashNow = hashrateMhs != null ? hashrateMhs.doubleValue() : 0d;
        double hashAvg = hashNow;
        return Optional.of(new F2PoolWorkerSample(account.getName(), account.getCoin(), workerId, hashNow, hashAvg, lastShare));
    }

    private Object readPath(String json, String path) {
        if (!StringUtils.hasText(path)) {
            return null;
        }
        try {
            DocumentContext ctx = JsonPath.parse(json);
            return ctx.read(path);
        } catch (Exception ex) {
            return null;
        }
    }

    private String readString(DocumentContext ctx, String path) {
        if (!StringUtils.hasText(path)) {
            return null;
        }
        try {
            Object value = ctx.read(path);
            if (value == null) {
                return null;
            }
            return value.toString();
        } catch (Exception ex) {
            return null;
        }
    }

    private BigDecimal readDecimal(DocumentContext ctx, String path) {
        if (!StringUtils.hasText(path)) {
            return null;
        }
        try {
            Object value = ctx.read(path);
            return parseDecimal(value);
        } catch (Exception ex) {
            return null;
        }
    }

    private Integer readInt(DocumentContext ctx, String path) {
        if (!StringUtils.hasText(path)) {
            return null;
        }
        try {
            Object value = ctx.read(path);
            if (value instanceof Number number) {
                return number.intValue();
            }
            if (value instanceof String str && StringUtils.hasText(str)) {
                return Integer.parseInt(str.trim());
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private Optional<Instant> readInstant(DocumentContext ctx, String path) {
        if (!StringUtils.hasText(path)) {
            return Optional.empty();
        }
        try {
            Object value = ctx.read(path);
            return parseInstant(value);
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    private String readStringAt(List<?> row, Integer index) {
        if (row == null || index == null || index < 0 || index >= row.size()) {
            return null;
        }
        Object value = row.get(index);
        return value != null ? value.toString() : null;
    }

    private BigDecimal readDecimalAt(List<?> row, Integer index) {
        if (row == null || index == null || index < 0 || index >= row.size()) {
            return null;
        }
        Object value = row.get(index);
        return parseDecimal(value);
    }

    private Optional<Instant> readInstantAt(List<?> row, Integer index) {
        if (row == null || index == null || index < 0 || index >= row.size()) {
            return Optional.empty();
        }
        Object value = row.get(index);
        return parseInstant(value);
    }

    private Optional<PayoutItem> parsePayoutNode(Object node) {
        if (node == null) {
            return Optional.empty();
        }
        try {
            F2PoolProperties.Mapping mapping = v1Mapping();
            JsonNode jsonNode = objectMapper.valueToTree(node);
            DocumentContext ctx = JsonPath.parse(jsonNode);
            BigDecimal amount = readDecimal(ctx, mapping.getPayoutAmount());
            String txId = readString(ctx, mapping.getPayoutTxId());
            String dateText = readString(ctx, mapping.getPayoutDate());
            Optional<Instant> tsOpt = readInstant(ctx, mapping.getPayoutTimestamp());
            LocalDate date = parseDate(dateText, tsOpt.orElse(null));
            if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0 || date == null) {
                return Optional.empty();
            }
            return Optional.of(new PayoutItem(date, amount, txId, tsOpt.orElse(null)));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    private LocalDate parseDate(String dateText, Instant fallback) {
        if (StringUtils.hasText(dateText)) {
            try {
                return LocalDate.parse(dateText.trim(), DATE_FMT);
            } catch (Exception ignored) {
                // fall through
            }
        }
        if (fallback != null) {
            return LocalDate.ofInstant(fallback, BJT);
        }
        return null;
    }

    private JsonNode resolveTransactionsNode(JsonNode data) {
        if (data == null || data.isMissingNode()) {
            return null;
        }
        if (data.isArray()) {
            return data;
        }
        JsonNode txs = data.get("transactions");
        if (txs != null) {
            return txs;
        }
        txs = data.get("list");
        if (txs != null) {
            return txs;
        }
        txs = data.get("rows");
        if (txs != null) {
            return txs;
        }
        return null;
    }

    private PayoutItem parsePayoutV2Item(JsonNode tx) {
        if (tx == null || tx.isMissingNode()) {
            return null;
        }
        JsonNode extra = tx.get("payout_extra");
        BigDecimal amount = readDecimal(extra != null ? extra.get("value") : null);
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            BigDecimal changed = readDecimal(tx.get("changed_balance"));
            if (changed != null) {
                amount = changed.abs();
            }
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        String txId = readString(extra != null ? extra.get("tx_id") : null);
        if (!StringUtils.hasText(txId)) {
            txId = readString(extra != null ? extra.get("txid") : null);
        }
        Instant ts = readInstant(extra != null ? extra.get("paid_time") : null).orElse(null);
        if (ts == null) {
            ts = readInstant(tx.get("created_at")).orElse(null);
        }
        LocalDate date = ts != null ? LocalDate.ofInstant(ts, BJT) : null;
        if (date == null) {
            return null;
        }
        return new PayoutItem(date, amount, txId, ts);
    }

    private Optional<Instant> parseInstant(Object value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            long epoch;
            if (value instanceof Number number) {
                epoch = number.longValue();
            } else if (value instanceof String str && StringUtils.hasText(str)) {
                epoch = Long.parseLong(str.trim());
            } else {
                return Optional.empty();
            }
            if (Math.abs(epoch) > 10_000_000_000L) {
                return Optional.of(Instant.ofEpochMilli(epoch));
            }
            return Optional.of(Instant.ofEpochSecond(epoch));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    private Optional<Instant> readInstant(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Optional.empty();
        }
        if (node.isNumber()) {
            long epoch = node.asLong();
            if (Math.abs(epoch) > 10_000_000_000L) {
                return Optional.of(Instant.ofEpochMilli(epoch));
            }
            return Optional.of(Instant.ofEpochSecond(epoch));
        }
        if (node.isTextual()) {
            return parseInstant(node.asText());
        }
        return Optional.empty();
    }

    private Optional<Instant> parseInstant(String text) {
        if (!StringUtils.hasText(text)) {
            return Optional.empty();
        }
        String trimmed = text.trim();
        try {
            long epoch = Long.parseLong(trimmed);
            if (Math.abs(epoch) > 10_000_000_000L) {
                return Optional.of(Instant.ofEpochMilli(epoch));
            }
            return Optional.of(Instant.ofEpochSecond(epoch));
        } catch (NumberFormatException ignored) {
            // fall through
        }
        try {
            return Optional.of(Instant.parse(trimmed));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private String readString(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return node.asText();
    }

    private BigDecimal readDecimal(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return BigDecimal.valueOf(node.asDouble());
        }
        if (node.isTextual()) {
            String t = node.asText();
            if (!StringUtils.hasText(t)) {
                return null;
            }
            try {
                return new BigDecimal(t.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private BigDecimal parseDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        if (value instanceof String str && StringUtils.hasText(str)) {
            String trimmed = str.trim();
            try {
                return new BigDecimal(trimmed);
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return null;
    }

    private BigDecimal toMhs(BigDecimal hashrate, String unit) {
        if (hashrate == null) {
            return null;
        }
        if (!StringUtils.hasText(unit)) {
            return hashrate;
        }
        String normalized = unit.trim().toUpperCase(Locale.ROOT);
        BigDecimal multiplier = HASHRATE_UNITS.get(normalized);
        if (multiplier == null) {
            return hashrate;
        }
        return hashrate.multiply(multiplier);
    }

    public record ParsedAccountOverview(BigDecimal hashrateHps, Integer workers, Integer activeWorkers,
                                        BigDecimal fixedValue) {
    }

    public record ParsedAssetsBalance(BigDecimal balance,
                                      BigDecimal immatureBalance,
                                      BigDecimal paid,
                                      BigDecimal totalIncome,
                                      BigDecimal yesterdayIncome,
                                      BigDecimal estimatedTodayIncome) {
    }

    public record PayoutItem(LocalDate payoutDate, BigDecimal amount, String txId, Instant timestamp) {
    }

    public record V2Error(int code, String msg) {
    }

    private F2PoolProperties.Mapping v1Mapping() {
        F2PoolProperties.V1 v1 = properties.getV1();
        if (v1 == null || v1.getMapping() == null) {
            return new F2PoolProperties.Mapping();
        }
        return v1.getMapping();
    }
}
