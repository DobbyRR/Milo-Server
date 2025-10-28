package com.synclab.miloserver.opcua;

import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
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
    private final ScheduledExecutorService simulationExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r);
                t.setDaemon(true);
                return t;
            });
    private ScheduledFuture<?> simulationTask;
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);

    protected UnitLogic(String name, UaFolderNode machineFolder) {
        this.name = name;
        this.machineFolder = machineFolder;
    }

    public abstract void setupVariables(MultiMachineNameSpace ns);
    public abstract void onCommand(MultiMachineNameSpace ns, String command);
    public abstract void simulateStep(MultiMachineNameSpace ns);

    /** 공통 telemetry 등록 (PackML/OEE 기반) */
    protected void setupCommonTelemetry(MultiMachineNameSpace ns) {
//        telemetryNodes.put("equipment_id", ns.addVariableNode(machineFolder, name + ".equipment_id", equipmentId));
        telemetryNodes.put("equipment_id", ns.addVariableNode(machineFolder,".equipment_id", equipmentId));
        telemetryNodes.put("process_id", ns.addVariableNode(machineFolder,".process_id", processId));
        telemetryNodes.put("unit_type", ns.addVariableNode(machineFolder, ".unit_type", unitType));
        telemetryNodes.put("line_id", ns.addVariableNode(machineFolder,  ".line_id", lineId));
        telemetryNodes.put("machine_no", ns.addVariableNode(machineFolder, ".machine_no", machineNo));
        telemetryNodes.put("state", ns.addVariableNode(machineFolder,  ".state", state));
        telemetryNodes.put("mode_state", ns.addVariableNode(machineFolder, ".mode_state", modeState));
        telemetryNodes.put("PPM", ns.addVariableNode(machineFolder,  ".PPM", ppm));
        telemetryNodes.put("cycle_time", ns.addVariableNode(machineFolder, ".cycle_time", cycleTime));
        telemetryNodes.put("uptime", ns.addVariableNode(machineFolder,  ".uptime", uptime));
        telemetryNodes.put("downtime", ns.addVariableNode(machineFolder,  ".downtime", downtime));
        telemetryNodes.put("availability", ns.addVariableNode(machineFolder,  ".availability", availability));
        telemetryNodes.put("performance", ns.addVariableNode(machineFolder,  ".performance", performance));
        telemetryNodes.put("quality_rate", ns.addVariableNode(machineFolder,".quality_rate", qualityRate));
        telemetryNodes.put("OEE", ns.addVariableNode(machineFolder, ".OEE", oee));
        telemetryNodes.put("alarm_code", ns.addVariableNode(machineFolder,  ".alarm_code", alarmCode));
        telemetryNodes.put("alarm_level", ns.addVariableNode(machineFolder,  ".alarm_level", alarmLevel));
        telemetryNodes.put("energy_consumption", ns.addVariableNode(machineFolder, ".energy_consumption", energyConsumption));
        telemetryNodes.put("last_maintenance", ns.addVariableNode(machineFolder, ".last_maintenance", lastMaintenance.toString()));
        telemetryNodes.put("tray_id", ns.addVariableNode(machineFolder,".tray_id", trayId));
        telemetryNodes.put("order_no", ns.addVariableNode(machineFolder,".order_no", orderNo));
    }

    /** Telemetry 값 업데이트 및 구독자 알림 */
    protected void updateTelemetry(MultiMachineNameSpace ns, String key, Object value) {
        UaVariableNode node = telemetryNodes.get(key);
        if (node == null) return;

        Object previous = node.getValue().getValue().getValue();
        boolean changed = !Objects.equals(previous, value);

        ns.updateValue(node, value);

        if (changed && "air_pressure".equals(key)) {
            System.out.printf("[Telemetry] %s.%s -> %s%n", name, key, value);
        }
    }

    public String getName() { return name; }

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
}
