package com.synclab.miloserver.opcua;

import org.eclipse.milo.opcua.sdk.server.nodes.AttributeContext;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode;
import org.eclipse.milo.opcua.sdk.server.nodes.delegates.AttributeDelegate;
import org.eclipse.milo.opcua.stack.core.AttributeId;
import org.eclipse.milo.opcua.stack.core.UaException;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 트레이(36개 단위) 기반 파이프라인 제어 로직.
 * 각 스테이지는 큐에 쌓인 트레이를 설비별로 순차 배정하며,
 * 설비는 트레이 완료 시 OK 수량만을 다음 스테이지로 전달한다.
 */
public class ProductionLineController {

    private static final int TRAY_SIZE = 36;

    private final MultiMachineNameSpace namespace;
    private final String lineName;
    private final UaFolderNode lineFolder;

    /** 전체 설비 목록 및 기본 상태 추적 */
    private final List<UnitLogic> machines = new ArrayList<>();
    private final Map<UnitLogic, Integer> machineProduction = new HashMap<>();
    private final Map<UnitLogic, Integer> machineOkCounts = new HashMap<>();
    private final Map<UnitLogic, Integer> machineNgCounts = new HashMap<>();
    private final Map<UnitLogic, String> machineStates = new HashMap<>();

    /** 스테이지별 설비 및 트레이 큐 */
    private final Map<Integer, List<UnitLogic>> stageMachines = new HashMap<>();
    private final List<Integer> stageOrder = new ArrayList<>();
    private final Map<Integer, Deque<Tray>> stageQueues = new HashMap<>();
    private final Map<UnitLogic, MachineAssignment> machineAssignments = new HashMap<>();

    /** 라인 텔레메트리 */
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

    /** 최종 OK 누적 및 트레이 ID */
    private int finalOkTotal = 0;
    private int trayCounter = 0;

    private static final class Tray {
        final int id;
        final int plannedQty;

        Tray(int id, int plannedQty) {
            this.id = id;
            this.plannedQty = plannedQty;
        }
    }

    private static final class MachineAssignment {
        final Tray tray;
        final int startProduced;
        final int startOk;
        final int startNg;

        MachineAssignment(Tray tray, int startProduced, int startOk, int startNg) {
            this.tray = tray;
            this.startProduced = startProduced;
            this.startOk = startOk;
            this.startNg = startNg;
        }
    }

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

        UaVariableNode commandNode = namespace.addVariableNode(lineFolder, lineQualifiedName(".command"), "");
        commandNode.setDisplayName(LocalizedText.english(lineName + " Command"));
        commandNode.setMinimumSamplingInterval(0.0);
        commandNode.setAccessLevel(org.eclipse.milo.opcua.sdk.core.AccessLevel.toValue(EnumSet.of(
                org.eclipse.milo.opcua.sdk.core.AccessLevel.CurrentRead,
                org.eclipse.milo.opcua.sdk.core.AccessLevel.CurrentWrite)));
        commandNode.setUserAccessLevel(commandNode.getAccessLevel());
        commandNode.setAttributeDelegate(new AttributeDelegate() {
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
        nodes.put("command", commandNode);
    }

    private String lineQualifiedName(String suffix) {
        return lineName + suffix;
    }

    void registerMachine(UnitLogic machine) {
        machines.add(machine);
        machineProduction.put(machine, 0);
        machineOkCounts.put(machine, 0);
        machineNgCounts.put(machine, 0);
        machineStates.put(machine, machine.state);

        int stageNo = machine.getMachineNo();
        stageMachines.computeIfAbsent(stageNo, k -> {
            stageOrder.add(stageNo);
            return new ArrayList<>();
        }).add(machine);

        machine.setLineController(this);
    }

    private synchronized void handleCommand(String command) {
        String[] tokens = command.split(":");
        String action = tokens[0].trim().toUpperCase();
        switch (action) {
            case "START":
                if (tokens.length < 3) {
                    System.err.printf("[%s] START requires START:<order>:<qty>%n", lineName);
                    return;
                }
                try {
                    startOrder(tokens[1], Integer.parseInt(tokens[2]));
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
        this.finalOkTotal = 0;
        this.trayCounter = 0;

        machineAssignments.clear();
        machineProduction.replaceAll((m, v) -> 0);
        machineOkCounts.replaceAll((m, v) -> 0);
        machineNgCounts.replaceAll((m, v) -> 0);
        machineStates.replaceAll((m, v) -> "IDLE");
        stageQueues.clear();
        stageOrder.sort(Integer::compareTo);
        stageOrder.forEach(stage -> stageQueues.put(stage, new ArrayDeque<>()));

        updateLineTelemetry();
        updateNode("order_produced_qty", 0);

        if (!stageOrder.isEmpty()) {
            int firstStage = stageOrder.get(0);
            enqueueInitialTrays(firstStage, targetQty);
            dispatchStage(firstStage);
        }
    }

    private void enqueueInitialTrays(int stageNo, int totalQty) {
        int remaining = totalQty;
        while (remaining > 0) {
            int qty = Math.min(TRAY_SIZE, remaining);
            enqueueTray(stageNo, qty);
            remaining -= qty;
        }
    }

    private void enqueueTray(int stageNo, int qty) {
        if (qty <= 0) return;
        Deque<Tray> queue = stageQueues.computeIfAbsent(stageNo, k -> new ArrayDeque<>());
        trayCounter++;
        queue.addLast(new Tray(trayCounter, qty));
    }

    public synchronized void onMachineProduced(UnitLogic machine, int producedQty, int targetQty) {
        machineProduction.put(machine, producedQty);
        MachineAssignment assignment = machineAssignments.get(machine);
        if (assignment == null) return;

        int producedForTray = producedQty - assignment.startProduced;
        if (producedForTray >= assignment.tray.plannedQty) {
            completeTray(machine);
        }
    }

    public synchronized void onMachineQualityChanged(UnitLogic machine, int okTotal, int ngTotal) {
        machineOkCounts.put(machine, okTotal);
        machineNgCounts.put(machine, ngTotal);
    }

    public synchronized void onMachineAckPendingChanged(UnitLogic machine, boolean pending) {
        if (pending) {
            if (isLastMachine(machine) && finalOkTotal >= targetQuantity) {
                awaitingAck = true;
                orderStatus = "WAITING_ACK";
                updateLineTelemetry();
            } else {
                machine.acknowledgeOrderCompletion(namespace);
            }
        } else if (machines.stream().noneMatch(m -> m.awaitingMesAck)) {
            awaitingAck = false;
            updateLineTelemetry();
            dispatchStage(machine.getMachineNo());
        }
    }

    public synchronized void onMachineStateChanged(UnitLogic machine, String newState) {
        machineStates.put(machine, newState);
        if (!orderActive && machines.stream().allMatch(m -> "IDLE".equalsIgnoreCase(machineStates.get(m)))) {
            resetLineState();
            return;
        }
        if ("IDLE".equalsIgnoreCase(newState) && orderActive) {
            dispatchStage(machine.getMachineNo());
        }
    }

    public synchronized void onMachineReset(UnitLogic machine) {
        machineAssignments.remove(machine);
        if (!orderActive) return;
        dispatchStage(machine.getMachineNo());
    }

    private void dispatchStage(int stageNo) {
        Deque<Tray> queue = stageQueues.get(stageNo);
        if (queue == null || queue.isEmpty()) return;

        List<UnitLogic> stageList = stageMachines.get(stageNo);
        if (stageList == null || stageList.isEmpty()) return;

        for (UnitLogic machine : stageList) {
            if (queue.isEmpty()) break;
            if (machineAssignments.containsKey(machine)) continue;
            if (machine.awaitingMesAck) continue;
            if (!"IDLE".equalsIgnoreCase(machineStates.getOrDefault(machine, "IDLE"))) continue;

            Tray tray = queue.pollFirst();
            if (tray == null) break;
            assignTray(machine, tray);
        }
    }

    private void assignTray(UnitLogic machine, Tray tray) {
        try {
            int machinePpm = machine.getDefaultPpm();
            int currentProduced = machineProduction.getOrDefault(machine, 0);
            int currentOk = machineOkCounts.getOrDefault(machine, 0);
            int currentNg = machineNgCounts.getOrDefault(machine, 0);

            machine.startOrder(namespace, currentOrderNo, tray.plannedQty, machinePpm);
            machine.updateProducedQuantity(namespace, currentProduced);
            machine.updateQualityCounts(namespace, currentOk, currentNg);
            machineStates.put(machine, machine.state);

            machineAssignments.put(machine, new MachineAssignment(tray, currentProduced, currentOk, currentNg));
        } catch (Exception ex) {
            System.err.printf("[%s] Failed to dispatch tray %d to %s: %s%n",
                    lineName, tray.id, machine.getName(), ex.getMessage());
            enqueueTray(machine.getMachineNo(), tray.plannedQty);
        }
    }

    private void completeTray(UnitLogic machine) {
        MachineAssignment assignment = machineAssignments.remove(machine);
        if (assignment == null) return;

        int totalOk = machineOkCounts.getOrDefault(machine, 0);
        int totalNg = machineNgCounts.getOrDefault(machine, 0);
        int trayOk = Math.max(0, totalOk - assignment.startOk);
        int trayNg = Math.max(0, totalNg - assignment.startNg);

        int stageNo = machine.getMachineNo();
        int nextIndex = stageOrder.indexOf(stageNo) + 1;

        if (nextIndex < stageOrder.size()) {
            if (trayOk > 0) {
                int nextStage = stageOrder.get(nextIndex);
                enqueueTray(nextStage, trayOk);
                dispatchStage(nextStage);
            }
        } else {
            finalOkTotal += trayOk;
            updateNode("order_produced_qty", finalOkTotal);
            if (orderActive && finalOkTotal >= targetQuantity) {
                awaitingAck = true;
                orderStatus = "WAITING_ACK";
                updateLineTelemetry();
            }
        }

        int producedTotal = machineProduction.getOrDefault(machine, 0);
        machine.acknowledgeOrderCompletion(namespace);
        machine.updateProducedQuantity(namespace, producedTotal);
        machine.updateQualityCounts(namespace, totalOk, totalNg);
        machineStates.put(machine, machine.state);
        dispatchStage(stageNo);

        if (orderActive && finalOkTotal < targetQuantity) {
            ensureUpstreamSupply();
        }
    }

    private void ensureUpstreamSupply() {
        if (stageOrder.isEmpty() || !orderActive) return;
        int firstStage = stageOrder.get(0);
        if (finalOkTotal >= targetQuantity) return;

        Deque<Tray> queue = stageQueues.get(firstStage);
        boolean stageBusy = stageMachines.get(firstStage).stream()
                .anyMatch(machineAssignments::containsKey);

        if ((queue == null || queue.isEmpty()) && !stageBusy) {
            enqueueTray(firstStage, TRAY_SIZE);
            dispatchStage(firstStage);
        }
    }

    private synchronized void acknowledge() {
        machines.forEach(machine -> machine.acknowledgeOrderCompletion(namespace));
        orderStatus = "ACKED";
        awaitingAck = false;
        orderActive = false;
        stageQueues.clear();
        machineAssignments.clear();
        updateLineTelemetry();
    }

    private synchronized void stopLine() {
        machines.forEach(UnitLogic::requestSimulationStop);
        orderStatus = "STOPPING";
        orderActive = false;
        stageQueues.clear();
        machineAssignments.clear();
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

    private boolean isLastMachine(UnitLogic machine) {
        if (stageOrder.isEmpty()) return false;
        int lastStage = stageOrder.get(stageOrder.size() - 1);
        return machine.getMachineNo() == lastStage;
    }

    private synchronized void resetLineState() {
        orderStatus = "IDLE";
        orderActive = false;
        awaitingAck = false;
        orderNo = "";
        targetQuantity = 0;
        finalOkTotal = 0;
        trayCounter = 0;
        stageQueues.clear();
        machineAssignments.clear();
        updateLineTelemetry();
        updateNode("order_produced_qty", 0);
    }
}
