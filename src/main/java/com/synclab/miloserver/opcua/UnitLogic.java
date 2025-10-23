package com.synclab.miloserver.opcua;

import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;

public abstract class UnitLogic {
    protected final String name;
    protected final UaFolderNode folder;
    protected final Map<String, UaVariableNode> telemetryNodes = new HashMap<>();

    // 공통 텔레메트리 속성
    protected String equipmentId;
    protected String processId;
    protected String unitType;
    protected String lineId;
    protected int machineNo;
    protected String state = "IDLE";
    protected String modeState = "Automatic";
    protected int ppm = 0;
    protected double cycleTime = 0.0;
    protected double uptime = 0.0;
    protected double downtime = 0.0;
    protected double availability = 0.0;
    protected double performance = 0.0;
    protected double qualityRate = 0.0;
    protected double oee = 0.0;
    protected String alarmCode = "";
    protected String alarmLevel = "";
    protected double energyConsumption = 0.0;
    protected String lastMaintenance = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    protected String trayId = "";
    protected String orderNo = "";

    public UnitLogic(String name, UaFolderNode folder) {
        this.name = name;
        this.folder = folder;
    }

    // 공통 Telemetry 노드 등록
    public void setupCommonTelemetry(MultiMachineNameSpace ns) {
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
        telemetryNodes.put("last_maintenance", ns.addVariableNode(folder, name + ".last_maintenance", lastMaintenance));
        telemetryNodes.put("tray_id", ns.addVariableNode(folder, name + ".tray_id", trayId));
        telemetryNodes.put("order_no", ns.addVariableNode(folder, name + ".order_no", orderNo));
    }

    // 공통 Telemetry 값 갱신
    protected void updateTelemetry(String key, Object value) {
        if (telemetryNodes.containsKey(key)) {
            telemetryNodes.get(key).setValue(new DataValue(new Variant(value)));
        }
    }

    // 구현 필요 추상 메소드
    public abstract void setupVariables(MultiMachineNameSpace ns);

    public abstract void onCommand(MultiMachineNameSpace ns, String command);

    public abstract void simulateStep(MultiMachineNameSpace ns);
}
