package com.synclab.miloserver.opcua;

import org.eclipse.milo.opcua.sdk.server.nodes.AttributeContext;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode;
import org.eclipse.milo.opcua.sdk.server.nodes.delegates.AttributeDelegate;
import org.eclipse.milo.opcua.stack.core.AttributeId;
import org.eclipse.milo.opcua.stack.core.UaException;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ProductionLineController {

    private final MultiMachineNameSpace namespace;
    private final String lineName;
    private final UaFolderNode lineFolder;
    private final List<UnitLogic> machines = new ArrayList<>();
    private final Map<UnitLogic, Integer> machineProduction = new HashMap<>();
    private final Map<UnitLogic, String> machineStates = new HashMap<>();
    private final Map<UnitLogic, Boolean> machineStarted = new HashMap<>();
    private final Map<UnitLogic, Boolean> machineCompleted = new HashMap<>();
    private final Map<UnitLogic, Integer> machineOkCounts = new HashMap<>();
    private final Map<UnitLogic, Integer> machineNgCounts = new HashMap<>();
    private final Map<UnitLogic, Integer> machineOkCarryover = new HashMap<>();
    private final Map<UnitLogic, Integer> machineTargets = new HashMap<>();
    private final Map<Integer, List<UnitLogic>> stageMachines = new HashMap<>();
    private final List<Integer> stageOrder = new ArrayList<>();
    private final Map<Integer, Boolean> stageStarted = new HashMap<>();
    private final Map<Integer, Integer> stageOutputs = new HashMap<>();
    private final Set<Integer> completedStages = new HashSet<>();
    private final Map<Integer, Integer> stageRequirements = new HashMap<>();

    private final Map<String, UaVariableNode> nodes = new HashMap<>();

    private String orderStatus = "IDLE";
    private String orderNo = "";
    private int targetQuantity = 0;
    private int linePpm = 0;
    private boolean awaitingAck = false;
    private boolean orderActive = false;
    private String currentOrderNo = "";
    private int currentOrderTargetQty = 0;
    private int currentOrderPpm = 0;

    public ProductionLineController(MultiMachineNameSpace namespace, String lineName, UaFolderNode lineFolder) {
        this.namespace = namespace;
        this.lineName = lineName;
        this.lineFolder = lineFolder;

        initializeNodes();
    }

    private void initializeNodes() {
        nodes.put("order_no", namespace.addVariableNode(lineFolder, lineQualifiedName(".order_no"), orderNo));
        nodes.put("order_target_qty", namespace.addVariableNode(lineFolder, lineQualifiedName(".order_target_qty"), targetQuantity));
        nodes.put("order_produced_qty", namespace.addVariableNode(lineFolder, lineQualifiedName(".order_produced_qty"), 0));
        nodes.put("order_status", namespace.addVariableNode(lineFolder, lineQualifiedName(".order_status"), orderStatus));
        nodes.put("mes_ack_pending", namespace.addVariableNode(lineFolder, lineQualifiedName(".mes_ack_pending"), awaitingAck));
        nodes.put("order_ppm", namespace.addVariableNode(lineFolder, lineQualifiedName(".order_ppm"), linePpm));

        UaVariableNode commandNode = buildCommandNode();
        nodes.put("command", commandNode);
    }

    private UaVariableNode buildCommandNode() {
        UaVariableNode node = namespace.addVariableNode(lineFolder, lineQualifiedName(".command"), "");
        node.setDisplayName(LocalizedText.english(lineName + " Command"));
        node.setMinimumSamplingInterval(0.0);
        node.setAccessLevel(org.eclipse.milo.opcua.sdk.core.AccessLevel.toValue(EnumSet.of(
                org.eclipse.milo.opcua.sdk.core.AccessLevel.CurrentRead,
                org.eclipse.milo.opcua.sdk.core.AccessLevel.CurrentWrite)));
        node.setUserAccessLevel(org.eclipse.milo.opcua.sdk.core.AccessLevel.toValue(EnumSet.of(
                org.eclipse.milo.opcua.sdk.core.AccessLevel.CurrentRead,
                org.eclipse.milo.opcua.sdk.core.AccessLevel.CurrentWrite)));

        node.setAttributeDelegate(new AttributeDelegate() {
            @Override
            public void setAttribute(AttributeContext context,
                                    org.eclipse.milo.opcua.sdk.core.nodes.Node node,
                                    AttributeId attributeId,
                                    DataValue value) throws UaException {
                AttributeDelegate.super.setAttribute(context, node, attributeId, value);
                if (attributeId == AttributeId.Value) {
                    String raw = value.getValue() != null && value.getValue().getValue() != null
                            ? value.getValue().getValue().toString().trim()
                            : "";
                    if (!raw.isEmpty()) {
                        handleCommand(raw);
                    }
                }
            }
        });

        return node;
    }

    private String lineQualifiedName(String suffix) {
        return lineName + suffix;
    }

    void registerMachine(UnitLogic machine) {
        machines.add(machine);
        machineProduction.put(machine, 0);
        machineStates.put(machine, machine.state);
        machineStarted.put(machine, false);
        machineCompleted.put(machine, false);
        machineOkCounts.put(machine, 0);
        machineNgCounts.put(machine, 0);
        machineTargets.put(machine, 0);
        machineOkCarryover.put(machine, 0);
        int stageNo = machine.getMachineNo();
        if (!stageMachines.containsKey(stageNo)) {
            stageMachines.put(stageNo, new ArrayList<>());
            stageOrder.add(stageNo);
        }
        stageMachines.get(stageNo).add(machine);
        machine.setLineController(this);
    }

    private synchronized void handleCommand(String command) {
        String[] tokens = command.split(":");
        String action = tokens[0].trim().toUpperCase();

        switch (action) {
            case "START":
                if (tokens.length < 3) {
                    System.err.printf("[%s] START requires lineCommand START:<order>:<qty>%n", lineName);
                    return;
                }
                try {
                    String orderId = tokens[1];
                    int targetQty = Integer.parseInt(tokens[2]);
                    startOrder(orderId, targetQty);
                } catch (NumberFormatException ex) {
                    System.err.printf("[%s] Invalid START parameters '%s': %s%n", lineName, command, ex.getMessage());
                }
                break;
            case "ACK":
                acknowledge();
                break;
            case "STOP":
                stopLine();
                break;
            default:
                System.err.printf("[%s] Unsupported line command '%s'%n", lineName, command);
        }
    }

    private synchronized void startOrder(String orderId, int targetQty) {
        if (orderActive || awaitingAck) {
            System.err.printf("[%s] Line busy; cannot start new order.%n", lineName);
            return;
        }

        this.orderActive = true;
        this.awaitingAck = false;
        this.orderNo = orderId;
        this.targetQuantity = targetQty;
        this.linePpm = 0;
        this.orderStatus = "RUNNING";
        this.currentOrderNo = orderId;
        this.currentOrderTargetQty = targetQty;
        this.currentOrderPpm = 0;

        machineProduction.replaceAll((m, v) -> 0);
        machineStates.replaceAll((m, v) -> "IDLE");
        machineStarted.replaceAll((m, v) -> false);
        machineCompleted.replaceAll((m, v) -> false);
        machineOkCounts.replaceAll((m, v) -> 0);
        machineNgCounts.replaceAll((m, v) -> 0);
        machineTargets.replaceAll((m, v) -> 0);
        machineOkCarryover.replaceAll((m, v) -> 0);
        stageOutputs.clear();
        stageStarted.clear();
        completedStages.clear();
        stageRequirements.clear();
        stageOrder.sort(Integer::compareTo);
        stageOrder.forEach(stage -> {
            stageStarted.put(stage, false);
            stageOutputs.put(stage, 0);
        });

        updateLineTelemetry();
        updateNode("order_produced_qty", 0);

        if (!stageOrder.isEmpty()) {
            startStage(stageOrder.get(0), currentOrderTargetQty);
        }
    }

    private synchronized void acknowledge() {
        if (!awaitingAck) {
            System.err.printf("[%s] No machines awaiting MES ACK.%n", lineName);
        }
        machines.forEach(machine -> machine.acknowledgeOrderCompletion(namespace));
        this.orderStatus = "ACKED";
        this.awaitingAck = false;
        this.orderActive = false;
        updateLineTelemetry();
    }

    private synchronized void stopLine() {
        machines.forEach(UnitLogic::requestSimulationStop);
        this.orderStatus = "STOPPING";
        this.orderActive = false;
        updateLineTelemetry();
    }

    private void updateLineTelemetry() {
        updateNode("order_no", orderNo);
        updateNode("order_target_qty", targetQuantity);
        updateNode("order_ppm", linePpm);
        updateNode("order_status", orderStatus);
        updateNode("mes_ack_pending", awaitingAck);
    }

    private void updateNode(String key, Object value) {
        UaVariableNode node = nodes.get(key);
        if (node != null) {
            namespace.updateValue(node, value);
        }
    }

    public synchronized void onMachineProduced(UnitLogic machine, int producedQty, int targetQty) {
        machineProduction.put(machine, producedQty);
        if (targetQty > 0 && producedQty >= targetQty) {
            machineCompleted.put(machine, true);
        }
        checkStageCompletion(machine.getMachineNo());
    }

    public synchronized void onMachineQualityChanged(UnitLogic machine, int okTotal, int ngTotal) {
        machineOkCounts.put(machine, okTotal);
        machineNgCounts.put(machine, ngTotal);
        int stageNo = machine.getMachineNo();
        if (isLastStage(stageNo)) {
            int okSum = getStageOkTotal(stageNo);
            updateNode("order_produced_qty", okSum);
        }
        maybeStartDownstreamStage(stageNo);
    }

    public synchronized void onMachineAckPendingChanged(UnitLogic machine, boolean pending) {
        if (pending) {
            if (isLastMachine(machine)) {
                this.awaitingAck = true;
                this.orderStatus = "WAITING_ACK";
                updateLineTelemetry();
            } else {
                machine.acknowledgeOrderCompletion(namespace);
            }
        } else if (machines.stream().noneMatch(m -> m.awaitingMesAck)) {
            this.awaitingAck = false;
            updateLineTelemetry();
        }
    }

    public synchronized void onMachineStateChanged(UnitLogic machine, String newState) {
        machineStates.put(machine, newState);
        if (!orderActive && machines.stream().allMatch(m -> "IDLE".equalsIgnoreCase(machineStates.get(m)))) {
            resetLineState();
        }
    }

    public synchronized void onMachineReset(UnitLogic machine) {
        machineProduction.put(machine, 0);
        machineStarted.put(machine, false);
        machineCompleted.put(machine, false);
        if (machines.stream().allMatch(m -> machineProduction.get(m) == 0)) {
            updateNode("order_produced_qty", 0);
        }
    }

    private void startStage(int stageNo, int requestedQty) {
        if (Boolean.TRUE.equals(stageStarted.get(stageNo))) {
            return;
        }
        List<UnitLogic> stageList = stageMachines.get(stageNo);
        int cumulativeOk = stageOutputs.getOrDefault(stageNo, 0);
        int requirement = stageRequirements.computeIfAbsent(stageNo, k -> requestedQty);
        if (requirement <= 0 && requestedQty > 0) {
            requirement = requestedQty;
            stageRequirements.put(stageNo, requirement);
        }
        int remaining = requirement > 0 ? Math.max(0, requirement - cumulativeOk) : requestedQty;
        if (remaining <= 0) {
            stageStarted.put(stageNo, false);
            checkStageCompletion(stageNo);
            return;
        }
        if (stageList == null || stageList.isEmpty()) {
            stageOutputs.put(stageNo, cumulativeOk);
            onStageCompleted(stageNo);
            return;
        }
        stageStarted.put(stageNo, true);
        int stageSize = stageList.size();
        int traySize = Math.max(1, stageList.get(0).getUnitsPerCycle());
        int totalCycles = remaining <= 0 ? 0 : (int) Math.ceil((double) remaining / traySize);
        int baseCycles = stageSize == 0 ? 0 : totalCycles / stageSize;
        int extraCycles = stageSize == 0 ? 0 : totalCycles % stageSize;
        for (int i = 0; i < stageSize; i++) {
            UnitLogic machine = stageList.get(i);
            int cycles = baseCycles + (i < extraCycles ? 1 : 0);
            int machineTargetQty = cycles * traySize;
            machineTargets.put(machine, machineTargetQty);
            machineProduction.put(machine, 0);
            if (machineTargetQty <= 0) {
                machineStarted.put(machine, false);
                machineCompleted.put(machine, true);
                continue;
            }
            int carried = machineOkCarryover.getOrDefault(machine, 0);
            int currentOk = machineOkCounts.getOrDefault(machine, 0);
            if (currentOk > 0) {
                machineOkCarryover.put(machine, carried + currentOk);
            }
            machineOkCounts.put(machine, 0);
            machineStarted.put(machine, true);
            machineCompleted.put(machine, false);
            try {
                int machinePpm = machine.getDefaultPpm();
                machine.startOrder(namespace, currentOrderNo, machineTargetQty, machinePpm);
            } catch (Exception ex) {
                System.err.printf("[%s] Failed to start machine %s: %s%n",
                        lineName, machine.getName(), ex.getMessage());
                machineCompleted.put(machine, true);
                machineTargets.put(machine, 0);
            }
        }
        checkStageCompletion(stageNo);
    }

    private void checkStageCompletion(int stageNo) {
        if (completedStages.contains(stageNo)) {
            return;
        }
        if (isStageComplete(stageNo)) {
            onStageCompleted(stageNo);
        }
    }

    private boolean isStageComplete(int stageNo) {
        List<UnitLogic> stageList = stageMachines.get(stageNo);
        if (stageList == null || stageList.isEmpty()) {
            return true;
        }
        for (UnitLogic machine : stageList) {
            int target = machineTargets.getOrDefault(machine, 0);
            if (target > 0 && !Boolean.TRUE.equals(machineCompleted.get(machine))) {
                return false;
            }
        }
        return true;
    }

    private void onStageCompleted(int stageNo) {
        int okSum = getStageOkTotal(stageNo);
        stageOutputs.put(stageNo, okSum);
        int requirement = stageRequirements.getOrDefault(stageNo, 0);
        if (requirement > 0 && okSum < requirement) {
            stageStarted.put(stageNo, false);
            List<UnitLogic> stageList = stageMachines.get(stageNo);
            if (stageList != null) {
                stageList.forEach(machine -> {
                    machineStarted.put(machine, false);
                    machineTargets.put(machine, 0);
                });
            }
            int deficit = requirement - okSum;
            if (deficit > 0) {
                startStage(stageNo, deficit);
            }
            return;
        }
        if (completedStages.contains(stageNo)) {
            return;
        }
        completedStages.add(stageNo);
        stageStarted.put(stageNo, false);
        List<UnitLogic> stageList = stageMachines.get(stageNo);
        if (stageList != null) {
            stageList.forEach(machine -> machineStarted.put(machine, false));
        }
        int index = stageOrder.indexOf(stageNo);
        if (index == -1) {
            return;
        }
        if (index == stageOrder.size() - 1) {
            updateNode("order_produced_qty", okSum);
            if (orderActive) {
                awaitingAck = true;
                orderStatus = "WAITING_ACK";
                updateLineTelemetry();
            }
        } else {
            int nextStage = stageOrder.get(index + 1);
            startStage(nextStage, okSum);
        }
    }

    private int getStageOkTotal(int stageNo) {
        List<UnitLogic> stageList = stageMachines.get(stageNo);
        if (stageList == null || stageList.isEmpty()) {
            return 0;
        }
        return stageList.stream()
                .mapToInt(this::getMachineStageOk)
                .sum();
    }

    private int getMachineStageOk(UnitLogic machine) {
        return machineOkCarryover.getOrDefault(machine, 0)
                + machineOkCounts.getOrDefault(machine, 0);
    }

    private void maybeStartDownstreamStage(int stageNo) {
        int index = stageOrder.indexOf(stageNo);
        if (index == -1 || index >= stageOrder.size() - 1) {
            return;
        }
        int nextStage = stageOrder.get(index + 1);
        if (Boolean.TRUE.equals(stageStarted.get(nextStage))) {
            return;
        }
        int availableOk = getStageOkTotal(stageNo);
        if (availableOk > 0) {
            int requested = stageRequirements.getOrDefault(nextStage, currentOrderTargetQty);
            if (requested <= 0) {
                requested = currentOrderTargetQty;
            }
            startStage(nextStage, requested);
        }
    }

    private boolean isLastStage(int stageNo) {
        return !stageOrder.isEmpty() && stageOrder.get(stageOrder.size() - 1) == stageNo;
    }

    private boolean isLastMachine(UnitLogic machine) {
        return isLastStage(machine.getMachineNo());
    }

    private void resetLineState() {
        this.orderActive = false;
        this.awaitingAck = false;
        this.orderNo = "";
        this.targetQuantity = 0;
        this.linePpm = 0;
        this.currentOrderNo = "";
        this.currentOrderTargetQty = 0;
        this.currentOrderPpm = 0;
        this.orderStatus = "IDLE";
        machineProduction.replaceAll((m, v) -> 0);
        machineStarted.replaceAll((m, v) -> false);
        machineCompleted.replaceAll((m, v) -> false);
        machineOkCounts.replaceAll((m, v) -> 0);
        machineNgCounts.replaceAll((m, v) -> 0);
        machineTargets.replaceAll((m, v) -> 0);
        machineOkCarryover.replaceAll((m, v) -> 0);
        stageStarted.clear();
        stageOutputs.clear();
        completedStages.clear();
        stageRequirements.clear();
        updateLineTelemetry();
        updateNode("order_produced_qty", 0);
    }
}
