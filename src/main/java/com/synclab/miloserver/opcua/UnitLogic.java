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

    protected int targetQuantity = 0;
    protected int producedQuantity = 0;
    protected int okCount = 0;
    protected int ngCount = 0;
    protected double productionAccumulator = 0.0;
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
    protected double energyConsumption = 0.0;
    protected int ppm = 0;
    protected int defaultPpm = 60;
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
    protected long stateStartTime = System.currentTimeMillis();
    private ProductionLineController lineController;

    protected UnitLogic(String name, UaFolderNode machineFolder) {
        this.name = name;
        this.machineFolder = machineFolder;
    }

    public abstract void setupVariables(MultiMachineNameSpace ns);
    public abstract void onCommand(MultiMachineNameSpace ns, String command);
    public abstract void simulateStep(MultiMachineNameSpace ns);

    /** 공통 telemetry 등록 (PackML/OEE 기반) */
    protected void setupCommonTelemetry(MultiMachineNameSpace ns) {
        telemetryNodes.put("equipment_id", ns.addVariableNode(machineFolder, name + ".equipment_id", equipmentId));
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
        telemetryNodes.put("energy_consumption", ns.addVariableNode(machineFolder, name + ".energy_consumption", energyConsumption));
        telemetryNodes.put("last_maintenance", ns.addVariableNode(machineFolder, name + ".last_maintenance", lastMaintenance.toString()));
        telemetryNodes.put("tray_id", ns.addVariableNode(machineFolder, name + ".tray_id", trayId));
        telemetryNodes.put("order_no", ns.addVariableNode(machineFolder, name + ".order_no", orderNo));
        telemetryNodes.put("order_target_qty", ns.addVariableNode(machineFolder, name + ".order_target_qty", targetQuantity));
        telemetryNodes.put("order_produced_qty", ns.addVariableNode(machineFolder, name + ".order_produced_qty", producedQuantity));
        telemetryNodes.put("order_ok_qty", ns.addVariableNode(machineFolder, name + ".order_ok_qty", okCount));
        telemetryNodes.put("order_ng_qty", ns.addVariableNode(machineFolder, name + ".order_ng_qty", ngCount));
        telemetryNodes.put("order_status", ns.addVariableNode(machineFolder, name + ".order_status", orderStatus));
        telemetryNodes.put("mes_ack_pending", ns.addVariableNode(machineFolder, name + ".mes_ack_pending", awaitingMesAck));
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

    protected void updateOrderStatus(MultiMachineNameSpace ns, String status) {
        this.orderStatus = status;
        updateTelemetry(ns, "order_status", status);
    }

    protected void updateProducedQuantity(MultiMachineNameSpace ns, int newQty) {
        producedQuantity = newQty;
        updateTelemetry(ns, "order_produced_qty", producedQuantity);
        if (lineController != null) {
            lineController.onMachineProduced(this, producedQuantity, targetQuantity);
        }
    }

    protected void updateQualityCounts(MultiMachineNameSpace ns, int okIncrement, int ngIncrement) {
        if (okIncrement != 0) {
            okCount += okIncrement;
            updateTelemetry(ns, "order_ok_qty", okCount);
        }
        if (ngIncrement != 0) {
            ngCount += ngIncrement;
            updateTelemetry(ns, "order_ng_qty", ngCount);
        }
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
                if (tokens.length < 4) {
                    System.err.printf("[%s] START command requires orderNo:targetQty:ppm but got '%s'%n", name, command);
                    return true;
                }
                try {
                    String orderId = tokens[1];
                    int targetQty = Integer.parseInt(tokens[2]);
                    int targetPpm = Integer.parseInt(tokens[3]);
                    handleStartCommand(ns, orderId, targetQty, targetPpm);
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

    protected void handleStartCommand(MultiMachineNameSpace ns, String orderId, int targetQty, int targetPpm) {
        startOrder(ns, orderId, targetQty, targetPpm);
    }

    public void setDefaultPpm(int defaultPpm) {
        if (defaultPpm > 0) {
            this.defaultPpm = defaultPpm;
        }
    }

    public int getDefaultPpm() {
        return defaultPpm;
    }

    public String getName() { return name; }

    public void setLineController(ProductionLineController lineController) {
        this.lineController = lineController;
    }

    public ProductionLineController getLineController() {
        return lineController;
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

    public synchronized void startOrder(MultiMachineNameSpace ns, String newOrderNo, int newTargetQuantity, int newPpm) {
        if (newTargetQuantity <= 0) {
            throw new IllegalArgumentException("targetQuantity must be > 0");
        }
        int effectivePpm = newPpm > 0 ? newPpm : defaultPpm;
        this.orderActive = true;
        this.orderNo = newOrderNo;
        this.targetQuantity = newTargetQuantity;
        this.ppm = effectivePpm;
        this.producedQuantity = 0;
        this.productionAccumulator = 0.0;
        updateTelemetry(ns, "order_no", orderNo);
        updateTelemetry(ns, "order_target_qty", targetQuantity);
        updateProducedQuantity(ns, 0);
        updateTelemetry(ns, "PPM", ppm);
        updateOrderStatus(ns, "PREPARING");
        updateMesAckPending(ns, false);

        if (!"STARTING".equals(state) && !"EXECUTE".equals(state)) {
            changeState(ns, "STARTING");
        }
        startSimulation(ns);
    }

    protected boolean accumulateProduction(MultiMachineNameSpace ns, double secondsElapsed) {
        if (!orderActive || targetQuantity <= 0 || ppm <= 0) {
            return false;
        }
        productionAccumulator += (ppm / 60.0) * secondsElapsed;
        int producedIncrement = (int) productionAccumulator;
        if (producedIncrement > 0) {
            productionAccumulator -= producedIncrement;
            int updated = producedQuantity + producedIncrement;
            if (updated > targetQuantity) {
                updated = targetQuantity;
                productionAccumulator = 0.0;
            }
            if (updated != producedQuantity) {
                updateProducedQuantity(ns, updated);
            }
        }
        return producedQuantity >= targetQuantity;
    }

    protected void onOrderCompleted(MultiMachineNameSpace ns) {
        if (!awaitingMesAck) {
            updateOrderStatus(ns, "WAITING_ACK");
            updateMesAckPending(ns, true);
            changeState(ns, "COMPLETE");
        }
    }

    public synchronized void acknowledgeOrderCompletion(MultiMachineNameSpace ns) {
        if (!awaitingMesAck) {
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
        this.productionAccumulator = 0.0;
        this.orderActive = false;
        updateTelemetry(ns, "order_no", orderNo);
        updateTelemetry(ns, "order_target_qty", targetQuantity);
        updateProducedQuantity(ns, producedQuantity);
        updateOrderStatus(ns, "IDLE");
        updateMesAckPending(ns, false);
        if (lineController != null) {
            lineController.onMachineReset(this);
        }
    }
}
