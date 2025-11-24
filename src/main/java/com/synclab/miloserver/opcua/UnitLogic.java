package com.synclab.miloserver.opcua;

import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;

import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public abstract class UnitLogic {
    protected final String name;
    protected final UaFolderNode machineFolder;

    protected String state = "IDLE";
    protected String modeState = "Automatic";
    protected String unitType;
    protected String lineId;
    protected int machineNo;
    protected String equipmentCode;
    protected String processId;
    protected String orderNo = "";
    protected String orderItemCode = "";
    protected String trayId = "";
    protected final List<String> traySerials = new ArrayList<>();
    protected final List<String> trayRejectedSerials = new ArrayList<>();
    protected final Deque<String> trayPendingSerials = new ArrayDeque<>();
    protected final List<String> trayCompletedOkSerials = new ArrayList<>();
    protected String activeSerial = "";
    protected final int[] trayNgTypeCounts = new int[4];
    protected final int[] orderNgTypeCounts = new int[4];
    protected int lastNgType = 0;
    protected String lastNgName = "";
    private final Map<Integer, String> ngTypeNameMap = new HashMap<>();

    protected int targetQuantity = 0;
    protected int producedQuantity = 0;
    protected int okCount = 0;
    protected int ngCount = 0;
    protected double cycleAccumulator = 0.0;
    protected int unitsPerCycle = 1;
    protected int lastProducedIncrement = 0;
    protected boolean awaitingMesAck = false;
    protected boolean orderActive = false;
    protected String orderStatus = "IDLE";

    protected double uptime = 0.0;
    protected double downtime = 0.0;
    protected double availability = 100.0;
    protected double performance = 100.0;
    protected double qualityRate = 100.0;
    protected double oee = 100.0;
    protected double cycleTime = 0.0;
    protected double energyUsage = 0.0;
    protected int ppm = 0;
    protected int defaultPpm = 60;
    protected String alarmCode = "";
    protected String alarmLevel = "";
    protected OffsetDateTime lastMaintenance = OffsetDateTime.now();
    protected double idleEnergyBase = 0.2;
    protected double idleEnergyJitter = 0.05;
    protected double operatingEnergyBase = 1.0;
    protected double operatingEnergyJitter = 0.2;
    protected double energyUsageScale = 10.0;

    public enum AlarmSeverity {
        NOTICE(1, "NOTICE"),
        WARNING(2, "WARNING"),
        FAULT(3, "FAULT"),
        EMERGENCY(4, "EMERGENCY");

        private final int level;
        private final String display;

        AlarmSeverity(int level, String display) {
            this.level = level;
            this.display = display;
        }

        public int getLevel() {
            return level;
        }

        public String getDisplay() {
            return display;
        }
    }

    public enum AlarmCause {
        INTERNAL,
        EXTERNAL
    }

    protected static final class AlarmDefinition {
        private final String code;
        private final String name;
        private final AlarmSeverity severity;
        private final AlarmCause cause;

        private AlarmDefinition(String code, String name, AlarmSeverity severity, AlarmCause cause) {
            this.code = code;
            this.name = name;
            this.severity = severity;
            this.cause = cause;
        }
    }

    protected static final class AlarmScenario {
        private final AlarmDefinition definition;
        private final double triggerProbabilityPerSecond;
        private final long minDurationMs;
        private final long maxDurationMs;

        private AlarmScenario(AlarmDefinition definition,
                              double triggerProbabilityPerSecond,
                              long minDurationMs,
                              long maxDurationMs) {
            this.definition = definition;
            this.triggerProbabilityPerSecond = triggerProbabilityPerSecond;
            this.minDurationMs = minDurationMs;
            this.maxDurationMs = maxDurationMs;
        }
    }

    protected static final class ActiveAlarm {
        private final AlarmDefinition definition;
        private OffsetDateTime occurredAt;
        private OffsetDateTime clearedAt;
        private boolean active;
        private long expectedAutoClearMs;

        private ActiveAlarm(AlarmDefinition definition) {
            this.definition = definition;
        }
    }

    protected final Map<String, AlarmDefinition> alarmDefinitions = new HashMap<>();
    protected final List<AlarmScenario> alarmScenarios = new ArrayList<>();
    protected ActiveAlarm activeAlarm;

    protected final Map<String, UaVariableNode> telemetryNodes = new HashMap<>();
    private final ScheduledExecutorService simulationExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r);
                t.setDaemon(true);
                return t;
            });
    private ScheduledFuture<?> simulationTask;
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    protected long stateStartTime = System.currentTimeMillis();
    private ProductionLineController lineController;
    protected boolean continuousMode = false;

    protected UnitLogic(String name, UaFolderNode machineFolder) {
        this.name = name;
        this.machineFolder = machineFolder;
    }

    public abstract void setupVariables(MultiMachineNameSpace ns);
    public abstract void onCommand(MultiMachineNameSpace ns, String command);
    public abstract void simulateStep(MultiMachineNameSpace ns);

    /** 공통 telemetry 등록 (PackML/OEE 기반) */
    protected void setupCommonTelemetry(MultiMachineNameSpace ns) {
        telemetryNodes.put("equipment_code", ns.addVariableNode(machineFolder, name + ".equipment_code", equipmentCode));
        telemetryNodes.put("process_id", ns.addVariableNode(machineFolder, name + ".process_id", processId));
        telemetryNodes.put("unit_type", ns.addVariableNode(machineFolder, name + ".unit_type", unitType));
        telemetryNodes.put("line_id", ns.addVariableNode(machineFolder,  name + ".line_id", lineId));
        telemetryNodes.put("machine_no", ns.addVariableNode(machineFolder, name + ".machine_no", machineNo));
        telemetryNodes.put("state", ns.addVariableNode(machineFolder,  name + ".state", state));
        telemetryNodes.put("mode_state", ns.addVariableNode(machineFolder, name + ".mode_state", modeState));
        telemetryNodes.put("PPM", ns.addVariableNode(machineFolder,  name + ".PPM", ppm));
        telemetryNodes.put("cycle_time", ns.addVariableNode(machineFolder, name + ".cycle_time", cycleTime));
        telemetryNodes.put("uptime", ns.addVariableNode(machineFolder,  name + ".uptime", uptime));
        telemetryNodes.put("downtime", ns.addVariableNode(machineFolder,  name + ".downtime", downtime));
        telemetryNodes.put("availability", ns.addVariableNode(machineFolder,  name + ".availability", availability));
        telemetryNodes.put("performance", ns.addVariableNode(machineFolder,  name + ".performance", performance));
        telemetryNodes.put("quality_rate", ns.addVariableNode(machineFolder, name + ".quality_rate", qualityRate));
        telemetryNodes.put("OEE", ns.addVariableNode(machineFolder, name + ".OEE", oee));
        telemetryNodes.put("alarm_code", ns.addVariableNode(machineFolder,  name + ".alarm_code", alarmCode));
        telemetryNodes.put("alarm_level", ns.addVariableNode(machineFolder,  name + ".alarm_level", alarmLevel));
        telemetryNodes.put("alarm_name", ns.addVariableNode(machineFolder, name + ".alarm_name", ""));
        telemetryNodes.put("alarm_type", ns.addVariableNode(machineFolder, name + ".alarm_type", 0));
        telemetryNodes.put("alarm_cause", ns.addVariableNode(machineFolder, name + ".alarm_cause", ""));
        telemetryNodes.put("alarm_occurrence_time", ns.addVariableNode(machineFolder, name + ".alarm_occurrence_time", ""));
        telemetryNodes.put("alarm_release_time", ns.addVariableNode(machineFolder, name + ".alarm_release_time", ""));
        telemetryNodes.put("alarm_active", ns.addVariableNode(machineFolder, name + ".alarm_active", false));
        telemetryNodes.put("alarm_event_payload", ns.addVariableNode(machineFolder, name + ".alarm_event_payload", ""));
        telemetryNodes.put("energy_usage", ns.addVariableNode(machineFolder, name + ".energy_usage", energyUsage));
        telemetryNodes.put("last_maintenance", ns.addVariableNode(machineFolder, name + ".last_maintenance", lastMaintenance.toString()));
        telemetryNodes.put("tray_id", ns.addVariableNode(machineFolder, name + ".tray_id", trayId));
        telemetryNodes.put("tray_serials", ns.addVariableNode(machineFolder, name + ".tray_serials", ""));
        telemetryNodes.put("tray_ng_serials", ns.addVariableNode(machineFolder, name + ".tray_ng_serials", ""));
        telemetryNodes.put("tray_completed_ok_serials", ns.addVariableNode(machineFolder, name + ".tray_completed_ok_serials", ""));
        telemetryNodes.put("tray_completed_ng_serials", ns.addVariableNode(machineFolder, name + ".tray_completed_ng_serials", ""));
        telemetryNodes.put("tray_active_serial", ns.addVariableNode(machineFolder, name + ".tray_active_serial", ""));
        telemetryNodes.put("tray_pending_count", ns.addVariableNode(machineFolder, name + ".tray_pending_count", 0));
        telemetryNodes.put("tray_ok_count", ns.addVariableNode(machineFolder, name + ".tray_ok_count", 0));
        telemetryNodes.put("tray_ng_count", ns.addVariableNode(machineFolder, name + ".tray_ng_count", 0));
        telemetryNodes.put("order_ng_type", ns.addVariableNode(machineFolder, name + ".order_ng_type", 0));
        telemetryNodes.put("order_ng_type1_qty", ns.addVariableNode(machineFolder, name + ".order_ng_type1_qty", 0));
        telemetryNodes.put("order_ng_type2_qty", ns.addVariableNode(machineFolder, name + ".order_ng_type2_qty", 0));
        telemetryNodes.put("order_ng_type3_qty", ns.addVariableNode(machineFolder, name + ".order_ng_type3_qty", 0));
        telemetryNodes.put("order_ng_type4_qty", ns.addVariableNode(machineFolder, name + ".order_ng_type4_qty", 0));
        telemetryNodes.put("order_no", ns.addVariableNode(machineFolder, name + ".order_no", orderNo));
        telemetryNodes.put("order_target_qty", ns.addVariableNode(machineFolder, name + ".order_target_qty", targetQuantity));
        telemetryNodes.put("order_item_code", ns.addVariableNode(machineFolder, name + ".order_item_code", orderItemCode));
        telemetryNodes.put("order_produced_qty", ns.addVariableNode(machineFolder, name + ".order_produced_qty", producedQuantity));
        telemetryNodes.put("order_ok_qty", ns.addVariableNode(machineFolder, name + ".order_ok_qty", okCount));
        telemetryNodes.put("order_ng_qty", ns.addVariableNode(machineFolder, name + ".order_ng_qty", ngCount));
        telemetryNodes.put("order_ng_name", ns.addVariableNode(machineFolder, name + ".order_ng_name", ""));
        telemetryNodes.put("order_ng_types_payload", ns.addVariableNode(machineFolder, name + ".order_ng_types_payload", ""));
        telemetryNodes.put("order_status", ns.addVariableNode(machineFolder, name + ".order_status", orderStatus));
        telemetryNodes.put("mes_ack_pending", ns.addVariableNode(machineFolder, name + ".mes_ack_pending", awaitingMesAck));
        telemetryNodes.put("ng_event_payload", ns.addVariableNode(machineFolder, name + ".ng_event_payload", ""));
        telemetryNodes.put("order_summary_payload", ns.addVariableNode(machineFolder, name + ".order_summary_payload", ""));
    }

    /** Telemetry 값 업데이트 및 구독자 알림 */
    protected void updateTelemetry(MultiMachineNameSpace ns, String key, Object value) {
        UaVariableNode node = telemetryNodes.get(key);
        if (node == null) return;

        Object previous = node.getValue().getValue().getValue();
        boolean changed = !Objects.equals(previous, value);

        if (changed) {
            System.out.printf("[Telemetry-Update] %s.%s: %s -> %s%n",
                    name,
                    key,
                    String.valueOf(previous),
                    String.valueOf(value));
        }

        ns.updateValue(node, value);
    }

    protected void updateOrderStatus(MultiMachineNameSpace ns, String status) {
        String previousStatus = this.orderStatus;
        this.orderStatus = status;
        updateTelemetry(ns, "order_status", status);
        if ("WAITING_ACK".equals(status) && !"WAITING_ACK".equals(previousStatus)) {
            updateOrderSummaryPayload(ns);
        }
    }

    protected void updateProducedQuantity(MultiMachineNameSpace ns, int newQty) {
        producedQuantity = newQty;
        updateTelemetry(ns, "order_produced_qty", producedQuantity);
        if (targetQuantity > 0 && producedQuantity == targetQuantity) {
            updateOrderSummaryPayload(ns);
        }
        if (lineController != null) {
            lineController.onMachineProduced(this, producedQuantity, targetQuantity);
        }
    }

    protected void updateQualityCounts(MultiMachineNameSpace ns, int okValue, int ngValue) {
        this.okCount = okValue;
        this.ngCount = ngValue;
        updateTelemetry(ns, "order_ok_qty", okCount);
        updateTelemetry(ns, "order_ng_qty", ngCount);
        if (lineController != null) {
            lineController.onMachineQualityChanged(this, okCount, ngCount);
        }
    }

    public synchronized void updateOrderItemCode(MultiMachineNameSpace ns, String newItemCode) {
        String sanitized = newItemCode != null ? newItemCode.trim() : "";
        updateTelemetry(ns, "order_item_code", sanitized);
        this.orderItemCode = sanitized;
    }

    /**
     * Force machine telemetry to reflect the latest line-level order metadata so stale
     * order_no values do not leak out before trays are assigned.
     */
    public synchronized void synchronizeOrderMetadata(MultiMachineNameSpace ns,
                                                      String newOrderNo,
                                                      String newItemCode) {
        String sanitizedOrderNo = newOrderNo != null ? newOrderNo.trim() : "";
        if (!Objects.equals(this.orderNo, sanitizedOrderNo)) {
            this.orderNo = sanitizedOrderNo;
            updateTelemetry(ns, "order_no", this.orderNo);
        }
        if (newItemCode != null) {
            updateOrderItemCode(ns, newItemCode);
        }
    }

    protected void updateTrayTelemetry(MultiMachineNameSpace ns) {
        refreshPendingSerialsView();
        updateTelemetry(ns, "tray_id", trayId);
        updateTelemetry(ns, "tray_serials", serializeSerials(traySerials));
        updateTelemetry(ns, "tray_ng_serials", serializeSerials(trayRejectedSerials));
        updateTelemetry(ns, "tray_completed_ok_serials", serializeSerials(trayCompletedOkSerials));
        updateTelemetry(ns, "tray_completed_ng_serials", serializeSerials(trayRejectedSerials));
        updateTelemetry(ns, "tray_active_serial", activeSerial);
        updateTelemetry(ns, "tray_pending_count", traySerials.size());
        updateTelemetry(ns, "tray_ok_count", trayCompletedOkSerials.size());
        updateTelemetry(ns, "tray_ng_count", trayRejectedSerials.size());
    }

    private String serializeSerials(List<String> serials) {
        return serials.isEmpty() ? "" : String.join(",", serials);
    }

    protected synchronized void clearTrayContext(MultiMachineNameSpace ns) {
        trayId = "";
        traySerials.clear();
        trayRejectedSerials.clear();
        trayPendingSerials.clear();
        trayCompletedOkSerials.clear();
        activeSerial = "";
        Arrays.fill(trayNgTypeCounts, 0);
        lastNgType = 0;
        updateTrayTelemetry(ns);
        updateNgTelemetry(ns);
    }

    public synchronized void assignTray(MultiMachineNameSpace ns, String newTrayId, List<String> okSerials) {
        this.trayId = newTrayId != null ? newTrayId : "";
        traySerials.clear();
        trayRejectedSerials.clear();
        trayPendingSerials.clear();
        trayCompletedOkSerials.clear();
        activeSerial = "";
        Arrays.fill(trayNgTypeCounts, 0);
        lastNgType = 0;
        if (okSerials != null) {
            trayPendingSerials.addAll(okSerials);
        }
        refreshPendingSerialsView();
        updateTrayTelemetry(ns);
        updateNgTelemetry(ns);
    }

    public synchronized String acquireNextSerial(MultiMachineNameSpace ns) {
        if (activeSerial != null && !activeSerial.isEmpty()) {
            return activeSerial;
        }
        if (trayPendingSerials.isEmpty()) {
            return "";
        }
        activeSerial = trayPendingSerials.pollFirst();
        refreshPendingSerialsView();
        updateTrayTelemetry(ns);
        return activeSerial;
    }

    public synchronized void completeActiveSerialOk(MultiMachineNameSpace ns) {
        if (activeSerial == null || activeSerial.isEmpty()) {
            return;
        }
        trayCompletedOkSerials.add(activeSerial);
        activeSerial = "";
        updateTrayTelemetry(ns);
        updateNgName(ns, "");
    }

    public synchronized void completeActiveSerialNg(MultiMachineNameSpace ns, int ngType) {
        if (activeSerial == null || activeSerial.isEmpty()) {
            return;
        }
        trayRejectedSerials.add(activeSerial);
        trayCompletedOkSerials.remove(activeSerial);
        int cumulativeTypeCount = 1;
        if (ngType >= 1 && ngType <= trayNgTypeCounts.length) {
            trayNgTypeCounts[ngType - 1]++;
            orderNgTypeCounts[ngType - 1]++;
            cumulativeTypeCount = orderNgTypeCounts[ngType - 1];
            lastNgType = ngType;
        }
        activeSerial = "";
        updateTrayTelemetry(ns);
        updateNgTelemetry(ns);
        updateNgName(ns, resolveNgTypeName(ngType));
        publishNgEvent(ns, ngType, cumulativeTypeCount);
    }

    public synchronized boolean hasMoreSerials() {
        return !trayPendingSerials.isEmpty() || (activeSerial != null && !activeSerial.isEmpty());
    }

    public synchronized boolean isTrayProcessingComplete() {
        return trayPendingSerials.isEmpty() && (activeSerial == null || activeSerial.isEmpty());
    }

    public synchronized void markTraySerials(MultiMachineNameSpace ns, List<String> okSerials, List<String> ngSerials) {
        trayCompletedOkSerials.clear();
        trayRejectedSerials.clear();
        if (okSerials != null) {
            trayCompletedOkSerials.addAll(okSerials);
        }
        if (ngSerials != null) {
            trayRejectedSerials.addAll(ngSerials);
        }
        updateTrayTelemetry(ns);
    }

    public synchronized void rejectTraySerial(MultiMachineNameSpace ns, String serial) {
        if (serial == null || serial.isBlank()) return;
        trayPendingSerials.remove(serial);
        trayRejectedSerials.add(serial);
        refreshPendingSerialsView();
        updateTrayTelemetry(ns);
    }

    public synchronized List<String> getTraySerialsSnapshot() {
        refreshPendingSerialsView();
        return new ArrayList<>(traySerials);
    }

    public synchronized List<String> getTrayCompletedOkSerialsSnapshot() {
        return new ArrayList<>(trayCompletedOkSerials);
    }

    public synchronized List<String> getTrayRejectedSerialsSnapshot() {
        return new ArrayList<>(trayRejectedSerials);
    }

    private void refreshPendingSerialsView() {
        traySerials.clear();
        traySerials.addAll(trayPendingSerials);
    }

    protected void updateNgTelemetry(MultiMachineNameSpace ns) {
        updateTelemetry(ns, "order_ng_type", lastNgType);
        updateTelemetry(ns, "order_ng_type1_qty", orderNgTypeCounts[0]);
        updateTelemetry(ns, "order_ng_type2_qty", orderNgTypeCounts[1]);
        updateTelemetry(ns, "order_ng_type3_qty", orderNgTypeCounts[2]);
        updateTelemetry(ns, "order_ng_type4_qty", orderNgTypeCounts[3]);
        updateTelemetry(ns, "order_ng_name", lastNgName);
        publishNgTypePayload(ns);
    }

    protected void resetOrderNgCounts(MultiMachineNameSpace ns) {
        Arrays.fill(orderNgTypeCounts, 0);
        lastNgType = 0;
        lastNgName = "";
        updateNgTelemetry(ns);
    }

    protected void resetNgTelemetry(MultiMachineNameSpace ns) {
        Arrays.fill(trayNgTypeCounts, 0);
        resetOrderNgCounts(ns);
    }

    protected void registerNgTypeNames(String... names) {
        ngTypeNameMap.clear();
        if (names == null) {
            return;
        }
        for (int i = 0; i < names.length; i++) {
            String name = names[i];
            if (name != null && !name.isBlank()) {
                ngTypeNameMap.put(i + 1, name.trim());
            }
        }
    }

    private String resolveNgTypeName(int ngType) {
        String name = ngTypeNameMap.getOrDefault(ngType, "");
        lastNgName = name;
        return name;
    }

    private void updateNgName(MultiMachineNameSpace ns, String name) {
        lastNgName = name == null ? "" : name;
        updateTelemetry(ns, "order_ng_name", lastNgName);
    }

    private void publishNgEvent(MultiMachineNameSpace ns, int ngType, int ngQty) {
        String payload = String.format(
                "{\"equipmentCode\":\"%s\",\"order_no\":\"%s\",\"ng_type\":%d,\"ng_name\":\"%s\",\"ng_qty\":%d}",
                equipmentCode == null ? "" : equipmentCode,
                orderNo == null ? "" : orderNo,
                ngType,
                lastNgName == null ? "" : lastNgName,
                Math.max(ngQty, 0)
        );
        updateTelemetry(ns, "ng_event_payload", payload);
    }

    private void publishNgTypePayload(MultiMachineNameSpace ns) {
        StringBuilder builder = new StringBuilder();
        builder.append("{\"equipmentCode\":\"")
                .append(escapeJson(equipmentCode))
                .append("\",\"order_no\":\"")
                .append(escapeJson(orderNo))
                .append("\",\"types\":[");
        for (int i = 0; i < trayNgTypeCounts.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            int type = i + 1;
            builder.append("{\"type\":")
                    .append(type)
                    .append(",\"name\":\"")
                    .append(escapeJson(ngTypeNameMap.getOrDefault(type, "")))
                    .append("\",\"qty\":")
                    .append(Math.max(orderNgTypeCounts[i], 0))
                    .append('}');
        }
        builder.append("]}");
        updateTelemetry(ns, "order_ng_types_payload", builder.toString());
    }

    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\"", "\\\"");
    }

    private void updateOrderSummaryPayload(MultiMachineNameSpace ns) {
        String payload = String.format(
                "{\"equipmentCode\":\"%s\",\"order_produced_qty\":%d,\"order_ng_qty\":%d}",
                equipmentCode == null ? "" : equipmentCode,
                producedQuantity,
                ngCount
        );
        updateTelemetry(ns, "order_summary_payload", payload);
    }

    private void updateAlarmPayload(MultiMachineNameSpace ns,
                                    AlarmDefinition definition,
                                    OffsetDateTime occurredAt,
                                    OffsetDateTime clearedAt,
                                    Integer clearedUserId) {
        if (definition == null || occurredAt == null) {
            updateTelemetry(ns, "alarm_event_payload", "");
            return;
        }
        String userValue = clearedUserId == null ? "null" : String.valueOf(clearedUserId);
        String payload = String.format(
                "{\"equipmentCode\":\"%s\",\"alarm_code\":\"%s\",\"alarm_type\":%d,\"alarm_name\":\"%s\",\"alarm_level\":\"%s\",\"occurred_at\":\"%s\",\"cleared_at\":\"%s\",\"user\":%s}",
                equipmentCode == null ? "" : equipmentCode,
                definition.code == null ? "" : definition.code,
                definition.severity.getLevel(),
                definition.name == null ? "" : definition.name,
                definition.severity.getDisplay(),
                occurredAt.toString(),
                clearedAt == null ? "" : clearedAt.toString(),
                userValue
        );
        updateTelemetry(ns, "alarm_event_payload", payload);
    }

    public synchronized void beginContinuousOrder(MultiMachineNameSpace ns,
                                                  String newOrderNo,
                                                  int initialTargetQuantity,
                                                  int targetPpm) {
        beginContinuousOrder(ns, newOrderNo, initialTargetQuantity, targetPpm, null);
    }

    public synchronized void beginContinuousOrder(MultiMachineNameSpace ns,
                                                  String newOrderNo,
                                                  int initialTargetQuantity,
                                                  int targetPpm,
                                                  String newItemCode) {
        if (newOrderNo != null && !newOrderNo.isBlank()) {
            orderNo = newOrderNo;
            updateTelemetry(ns, "order_no", orderNo);
        }
        if (targetPpm > 0) {
            ppm = targetPpm;
            updateTelemetry(ns, "PPM", ppm);
        }
        if (initialTargetQuantity > 0) {
            targetQuantity += initialTargetQuantity;
            updateTelemetry(ns, "order_target_qty", targetQuantity);
        }
        if (newItemCode != null) {
            updateOrderItemCode(ns, newItemCode);
        }
        continuousMode = true;
        orderActive = true;
        awaitingMesAck = false;
        updateTelemetry(ns, "mes_ack_pending", awaitingMesAck);
        updateOrderStatus(ns, "EXECUTE");
        startSimulation(ns);
        if (!"EXECUTE".equals(state) && !"STARTING".equals(state)) {
            changeState(ns, "STARTING");
        }
    }

    public synchronized void appendOrderTarget(MultiMachineNameSpace ns, int additionalQuantity) {
        if (additionalQuantity <= 0) {
            return;
        }
        targetQuantity += additionalQuantity;
        updateTelemetry(ns, "order_target_qty", targetQuantity);
        if (!orderActive) {
            orderActive = true;
            updateOrderStatus(ns, "EXECUTE");
        }
        continuousMode = true;
        awaitingMesAck = false;
        updateTelemetry(ns, "mes_ack_pending", awaitingMesAck);
        startSimulation(ns);
        if (!"EXECUTE".equals(state) && !"STARTING".equals(state)) {
            changeState(ns, "STARTING");
        }
    }

    public synchronized boolean isContinuousMode() {
        return continuousMode;
    }

    public synchronized void endContinuousOrder() {
        continuousMode = false;
    }


    protected void updateMesAckPending(MultiMachineNameSpace ns, boolean pending) {
        awaitingMesAck = pending;
        updateTelemetry(ns, "mes_ack_pending", awaitingMesAck);
        if (lineController != null) {
            lineController.onMachineAckPendingChanged(this, awaitingMesAck);
        }
    }

    protected boolean handleCommonCommand(MultiMachineNameSpace ns, String command) {
        if (command == null || command.isBlank()) {
            return false;
        }

        String[] tokens = command.split(":");
        String action = tokens[0].trim().toUpperCase();

        switch (action) {
            case "START":
                if (tokens.length < 3) {
                    System.err.printf("[%s] START command requires at least orderNo:targetQty but got '%s'%n", name, command);
                    return true;
                }
                try {
                    String orderId = tokens[1];
                    int targetQty = Integer.parseInt(tokens[2]);
                    int targetPpm = 0;
                    if (tokens.length >= 4 && !tokens[3].isBlank()) {
                        targetPpm = Integer.parseInt(tokens[3]);
                    }
                    handleStartCommand(ns, orderId, targetQty, targetPpm, null);
                } catch (NumberFormatException ex) {
                    System.err.printf("[%s] Invalid START parameters '%s': %s%n", name, command, ex.getMessage());
                }
                return true;

            case "ACK":
                acknowledgeOrderCompletion(ns);
                return true;

            case "RESET":
                if (!awaitingMesAck) {
                    resetOrderState(ns);
                    changeState(ns, "IDLE");
                }
                return true;

            case "STOP":
                requestSimulationStop();
                changeState(ns, "STOPPING");
                return true;

            default:
                return false;
        }
    }

    protected AlarmDefinition registerAlarm(String code,
                                            String name,
                                            AlarmSeverity severity,
                                            AlarmCause cause) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("alarm code is required");
        }
        AlarmDefinition definition = new AlarmDefinition(
                code.trim(),
                name == null ? "" : name.trim(),
                severity == null ? AlarmSeverity.NOTICE : severity,
                cause == null ? AlarmCause.INTERNAL : cause
        );
        alarmDefinitions.put(definition.code, definition);
        return definition;
    }

    protected void registerAlarmScenario(AlarmDefinition definition,
                                         double triggerProbabilityPerSecond,
                                         long minDurationMs,
                                         long maxDurationMs) {
        if (definition == null) {
            return;
        }
        double sanitizedProbability = Math.max(0.0, Math.min(1.0, triggerProbabilityPerSecond));
        long sanitizedMin = Math.max(1000L, minDurationMs);
        long sanitizedMax = Math.max(sanitizedMin, maxDurationMs);
        alarmScenarios.add(new AlarmScenario(definition, sanitizedProbability, sanitizedMin, sanitizedMax));
    }

    protected void processAlarms(MultiMachineNameSpace ns) {
        long now = System.currentTimeMillis();
        if (activeAlarm != null && activeAlarm.active) {
            if (activeAlarm.expectedAutoClearMs > 0 && now >= activeAlarm.expectedAutoClearMs) {
                clearActiveAlarm(ns);
            }
            return;
        }
        if (!"EXECUTE".equals(state) || alarmScenarios.isEmpty()) {
            return;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (AlarmScenario scenario : alarmScenarios) {
            if (scenario == null || scenario.definition == null) {
                continue;
            }
            if (random.nextDouble() < scenario.triggerProbabilityPerSecond) {
                ActiveAlarm triggered = triggerAlarm(ns, scenario.definition);
                if (triggered != null) {
                    long extra = scenario.maxDurationMs > scenario.minDurationMs
                            ? random.nextLong(scenario.maxDurationMs - scenario.minDurationMs + 1)
                            : 0L;
                    triggered.expectedAutoClearMs = now + scenario.minDurationMs + extra;
                }
                break;
            }
        }
    }

    protected ActiveAlarm triggerAlarm(MultiMachineNameSpace ns, AlarmDefinition definition) {
        if (definition == null) {
            return null;
        }
        if (activeAlarm != null && activeAlarm.active) {
            return activeAlarm;
        }
        ActiveAlarm alarm = new ActiveAlarm(definition);
        alarm.active = true;
        alarm.occurredAt = OffsetDateTime.now();
        alarm.expectedAutoClearMs = 0;
        activeAlarm = alarm;
        this.alarmCode = definition.code;
        this.alarmLevel = definition.severity.getDisplay();
        updateTelemetry(ns, "alarm_code", alarmCode);
        updateTelemetry(ns, "alarm_level", alarmLevel);
        updateTelemetry(ns, "alarm_name", definition.name);
        updateTelemetry(ns, "alarm_type", definition.severity.getLevel());
        updateTelemetry(ns, "alarm_cause", definition.cause.name());
        updateTelemetry(ns, "alarm_occurrence_time", alarm.occurredAt.toString());
        updateTelemetry(ns, "alarm_release_time", "");
        updateTelemetry(ns, "alarm_active", true);
        updateAlarmPayload(ns, definition, alarm.occurredAt, null, null);

        String targetState = definition.cause == AlarmCause.INTERNAL ? "HOLD" : "SUSPEND";
        if (!targetState.equals(state)) {
            changeState(ns, targetState);
        }
        return alarm;
    }

    protected void clearActiveAlarm(MultiMachineNameSpace ns) {
        if (activeAlarm == null || !activeAlarm.active) {
            return;
        }
        activeAlarm.active = false;
        activeAlarm.clearedAt = OffsetDateTime.now();
        activeAlarm.expectedAutoClearMs = 0;
        updateTelemetry(ns, "alarm_release_time", activeAlarm.clearedAt.toString());
        updateTelemetry(ns, "alarm_active", false);
        int clearedUserId = ThreadLocalRandom.current().nextInt(1, 21);
        updateAlarmPayload(ns, activeAlarm.definition, activeAlarm.occurredAt, activeAlarm.clearedAt, clearedUserId);

        if ("HOLD".equals(state) || "SUSPEND".equals(state)) {
            changeState(ns, "EXECUTE");
        }
    }

    protected boolean hasActiveAlarm() {
        return activeAlarm != null && activeAlarm.active;
    }

    protected void handleStartCommand(MultiMachineNameSpace ns, String orderId, int targetQty, int targetPpm, String itemCode) {
        startOrder(ns, orderId, targetQty, targetPpm, itemCode);
    }

    public void setDefaultPpm(int defaultPpm) {
        if (defaultPpm > 0) {
            this.defaultPpm = defaultPpm;
        }
    }

    public int getDefaultPpm() {
        return defaultPpm;
    }

    public void setUnitsPerCycle(int unitsPerCycle) {
        if (unitsPerCycle > 0) {
            this.unitsPerCycle = unitsPerCycle;
        }
    }

    public int getUnitsPerCycle() {
        return unitsPerCycle;
    }

    public int getLastProducedIncrement() {
        return lastProducedIncrement;
    }

    public void extendOrderTarget(MultiMachineNameSpace ns, int additionalQty) {
        if (additionalQty <= 0) {
            return;
        }
        targetQuantity += additionalQty;
        updateTelemetry(ns, "order_target_qty", targetQuantity);
        updateOrderStatus(ns, "EXECUTE");
        if (awaitingMesAck) {
            updateMesAckPending(ns, false);
        }
        if (!orderActive) {
            orderActive = true;
        }
        startSimulation(ns);
        if (!"EXECUTE".equals(state) && !"STARTING".equals(state)) {
            changeState(ns, "EXECUTE");
        }
    }

    public int getMachineNo() {
        return machineNo;
    }

    public void configureEnergyProfile(double idleBase, double idleJitter, double operatingBase, double operatingJitter) {
        if (idleBase >= 0) this.idleEnergyBase = idleBase;
        if (idleJitter >= 0) this.idleEnergyJitter = idleJitter;
        if (operatingBase >= 0) this.operatingEnergyBase = operatingBase;
        if (operatingJitter >= 0) this.operatingEnergyJitter = operatingJitter;
    }

    public String getName() { return name; }

    public void setLineController(ProductionLineController lineController) {
        this.lineController = lineController;
    }

    public ProductionLineController getLineController() {
        return lineController;
    }

    public String getUnitType() {
        return unitType;
    }

    protected synchronized void startSimulation(MultiMachineNameSpace ns) {
        stopRequested.set(false);
        if (simulationTask == null || simulationTask.isCancelled() || simulationTask.isDone()) {
            simulationTask = simulationExecutor.scheduleAtFixedRate(() -> {
                try {
                    simulateStep(ns);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }, 0, 1, TimeUnit.SECONDS);
        }
    }

    protected void changeState(MultiMachineNameSpace ns, String newState) {
        if (hasActiveAlarm()
                && ("HOLD".equals(state) || "SUSPEND".equals(state))
                && !"HOLD".equals(newState)
                && !"SUSPEND".equals(newState)) {
            System.out.printf("[%s] Active alarm prevents state change %s -> %s until cleared%n",
                    name,
                    state,
                    newState);
            return;
        }
        this.state = newState;
        this.stateStartTime = System.currentTimeMillis();
        updateTelemetry(ns, "state", newState);
        handleStateTransition(newState);
        System.out.printf("[%s] → %s%n", name, newState);
        if (lineController != null) {
            lineController.onMachineStateChanged(this, newState);
        }
    }

    protected void requestSimulationStop() {
        stopRequested.set(true);
    }

    protected void handleStateTransition(String newState) {
        if (stopRequested.get() && "IDLE".equalsIgnoreCase(newState)) {
            stopSimulation();
            stopRequested.set(false);
        }
    }

    protected synchronized void stopSimulation() {
        if (simulationTask != null) {
            simulationTask.cancel(false);
            simulationTask = null;
        }
    }

    public void shutdownSimulator() {
        stopSimulation();
        simulationExecutor.shutdownNow();
    }

    protected boolean timeInState(long ms) {
        return System.currentTimeMillis() - stateStartTime > ms;
    }

    protected void applyIdleDrift(MultiMachineNameSpace ns) {
        double jitter = (Math.random() - 0.5) * 2.0 * idleEnergyJitter;
        energyUsage = Math.max(0.0, (idleEnergyBase + jitter) * energyUsageScale);
        updateTelemetry(ns, "energy_usage", energyUsage);
    }

    protected void applyOperatingEnergy(MultiMachineNameSpace ns) {
        double jitter = (Math.random() - 0.5) * 2.0 * operatingEnergyJitter;
        energyUsage = Math.max(0.0, (operatingEnergyBase + jitter) * energyUsageScale);
        updateTelemetry(ns, "energy_usage", energyUsage);
    }

    public synchronized void startOrder(MultiMachineNameSpace ns, String newOrderNo, int newTargetQuantity, int newPpm) {
        startOrder(ns, newOrderNo, newTargetQuantity, newPpm, null);
    }

    public synchronized void startOrder(MultiMachineNameSpace ns,
                                        String newOrderNo,
                                        int newTargetQuantity,
                                        int newPpm,
                                        String newItemCode) {
        if (newTargetQuantity <= 0) {
            throw new IllegalArgumentException("targetQuantity must be > 0");
        }
        int effectivePpm = newPpm > 0 ? newPpm : defaultPpm;
        this.orderActive = true;
        this.orderNo = newOrderNo;
        this.targetQuantity = newTargetQuantity;
        this.ppm = effectivePpm;
        updateOrderItemCode(ns, newItemCode);
        this.producedQuantity = 0;
        this.cycleAccumulator = 0.0;
        this.lastProducedIncrement = 0;
        this.okCount = 0;
        this.ngCount = 0;
        Arrays.fill(trayNgTypeCounts, 0);
        resetOrderNgCounts(ns);
        updateTelemetry(ns, "order_no", orderNo);
        updateTelemetry(ns, "order_target_qty", targetQuantity);
        updateProducedQuantity(ns, 0);
        updateQualityCounts(ns, 0, 0);
        updateTelemetry(ns, "PPM", ppm);
        updateOrderStatus(ns, "PREPARING");
        updateMesAckPending(ns, false);

        if (!"STARTING".equals(state) && !"EXECUTE".equals(state)) {
            changeState(ns, "STARTING");
        }
        startSimulation(ns);
    }

    protected boolean accumulateProduction(MultiMachineNameSpace ns, double secondsElapsed) {
        lastProducedIncrement = 0;
        if (!orderActive || targetQuantity <= 0 || ppm <= 0 || unitsPerCycle <= 0) {
            return producedQuantity >= targetQuantity;
        }

        double cyclesPerMinute = (double) ppm / unitsPerCycle;
        cycleAccumulator += (cyclesPerMinute / 60.0) * secondsElapsed;
        int completedCycles = (int) cycleAccumulator;
        if (completedCycles > 0) {
            cycleAccumulator -= completedCycles;
            int increment = completedCycles * unitsPerCycle;
            int updated = producedQuantity + increment;
            if (updated > targetQuantity) {
                increment = targetQuantity - producedQuantity;
                updated = targetQuantity;
                cycleAccumulator = 0.0;
            }
            if (increment > 0) {
                lastProducedIncrement = increment;
                updateProducedQuantity(ns, updated);
            }
        }
        return producedQuantity >= targetQuantity;
    }

    protected void onOrderCompleted(MultiMachineNameSpace ns) {
        if (continuousMode) {
            updateOrderStatus(ns, "EXECUTE");
            updateMesAckPending(ns, false);
            return;
        }
        if (!awaitingMesAck) {
            updateOrderStatus(ns, "WAITING_ACK");
            updateMesAckPending(ns, true);
            changeState(ns, "COMPLETE");
        }
    }

    public synchronized void acknowledgeOrderCompletion(MultiMachineNameSpace ns) {
        if (!awaitingMesAck && !"COMPLETE".equalsIgnoreCase(state)) {
            return;
        }
        updateMesAckPending(ns, false);
        updateOrderStatus(ns, "ACKED");
        this.orderActive = false;
        changeState(ns, "RESETTING");
    }

    protected void resetOrderState(MultiMachineNameSpace ns) {
        this.orderNo = "";
        this.targetQuantity = 0;
        this.producedQuantity = 0;
        this.orderActive = false;
        this.cycleAccumulator = 0.0;
        this.lastProducedIncrement = 0;
        this.continuousMode = false;
        clearTrayContext(ns);
        updateTelemetry(ns, "order_no", orderNo);
        updateOrderItemCode(ns, "");
        updateTelemetry(ns, "order_target_qty", targetQuantity);
        updateProducedQuantity(ns, producedQuantity);
        updateQualityCounts(ns, 0, 0);
        resetNgTelemetry(ns);
        updateOrderStatus(ns, "IDLE");
        updateMesAckPending(ns, false);
        if (lineController != null) {
            lineController.onMachineReset(this);
        }
    }
}
