package com.synclab.miloserver.opcua;

import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

public abstract class UnitLogic {
    protected final String name;
    protected final UaFolderNode folder;

    protected String state = "IDLE";
    protected String modeState = "Automatic";
    protected String unitType;
    protected String lineId;
    protected int machineNo;
    protected String equipmentId;
    protected String processId;
    protected String orderNo = "";
    protected String trayId = "";

    protected double uptime = 0.0;
    protected double downtime = 0.0;
    protected double availability = 100.0;
    protected double performance = 100.0;
    protected double qualityRate = 100.0;
    protected double oee = 100.0;
    protected double cycleTime = 0.0;
    protected double energyConsumption = 0.0;
    protected int ppm = 0;
    protected String alarmCode = "";
    protected String alarmLevel = "";
    protected OffsetDateTime lastMaintenance = OffsetDateTime.now();

    protected final Map<String, UaVariableNode> telemetryNodes = new HashMap<>();

    protected UnitLogic(String name, UaFolderNode folder) {
        this.name = name;
        this.folder = folder;
    }

    public abstract void setupVariables(MultiMachineNameSpace ns);
    public abstract void onCommand(MultiMachineNameSpace ns, String command);
    public abstract void simulateStep(MultiMachineNameSpace ns);

    /** 공통 telemetry 등록 (PackML/OEE 기반) */
    protected void setupCommonTelemetry(MultiMachineNameSpace ns) {
        telemetryNodes.put("equipment_id", ns.addVariableNode(folder, name + ".equipment_id", equipmentId));
        telemetryNodes.put("process_id", ns.addVariableNode(folder, name + ".process_id", processId));
        telemetryNodes.put("unit_type", ns.addVariableNode(folder, name + ".unit_type", unitType));
        telemetryNodes.put("line_id", ns.addVariableNode(folder, name + ".line_id", lineId));
        telemetryNodes.put("machine_no", ns.addVariableNode(folder, name + ".machine_no", machineNo));
        telemetryNodes.put("state", ns.addVariableNode(folder, name + ".state", state));
        telemetryNodes.put("mode_state", ns.addVariableNode(folder, name + ".mode_state", modeState));
        telemetryNodes.put("PPM", ns.addVariableNode(folder, name + ".PPM", ppm));
        telemetryNodes.put("cycle_time", ns.addVariableNode(folder, name + ".cycle_time", cycleTime));
        telemetryNodes.put("uptime", ns.addVariableNode(folder, name + ".uptime", uptime));
        telemetryNodes.put("downtime", ns.addVariableNode(folder, name + ".downtime", downtime));
        telemetryNodes.put("availability", ns.addVariableNode(folder, name + ".availability", availability));
        telemetryNodes.put("performance", ns.addVariableNode(folder, name + ".performance", performance));
        telemetryNodes.put("quality_rate", ns.addVariableNode(folder, name + ".quality_rate", qualityRate));
        telemetryNodes.put("OEE", ns.addVariableNode(folder, name + ".OEE", oee));
        telemetryNodes.put("alarm_code", ns.addVariableNode(folder, name + ".alarm_code", alarmCode));
        telemetryNodes.put("alarm_level", ns.addVariableNode(folder, name + ".alarm_level", alarmLevel));
        telemetryNodes.put("energy_consumption", ns.addVariableNode(folder, name + ".energy_consumption", energyConsumption));
        telemetryNodes.put("last_maintenance", ns.addVariableNode(folder, name + ".last_maintenance", lastMaintenance.toString()));
        telemetryNodes.put("tray_id", ns.addVariableNode(folder, name + ".tray_id", trayId));
        telemetryNodes.put("order_no", ns.addVariableNode(folder, name + ".order_no", orderNo));
    }

    /** Telemetry 값 업데이트 및 구독자 알림 */
    protected void updateTelemetry(MultiMachineNameSpace ns, String key, Object value) {
        UaVariableNode node = telemetryNodes.get(key);
        if (node == null) return;

        DataValue newValue = new DataValue(new Variant(value), StatusCode.GOOD, null);
        node.setValue(newValue);

        // ✅ 정적 호출 대신 인스턴스의 subscriptionModel 사용
        ns.updateValue(node, value);
    }

    public String getName() { return name; }
}
