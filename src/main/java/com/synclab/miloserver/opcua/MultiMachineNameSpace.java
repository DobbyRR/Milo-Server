package com.synclab.miloserver.opcua;


import com.synclab.miloserver.machine.Machine1Logic;
import com.synclab.miloserver.machine.Machine2Logic;
import org.eclipse.milo.opcua.sdk.core.AccessLevel;
import org.eclipse.milo.opcua.sdk.core.Reference;
import org.eclipse.milo.opcua.sdk.core.nodes.VariableNode;
import org.eclipse.milo.opcua.sdk.server.OpcUaServer;
import org.eclipse.milo.opcua.sdk.server.api.DataItem;
import org.eclipse.milo.opcua.sdk.server.api.ManagedNamespace;
import org.eclipse.milo.opcua.sdk.server.api.MonitoredItem;
import org.eclipse.milo.opcua.sdk.server.api.NodeManager;
import org.eclipse.milo.opcua.sdk.server.nodes.AttributeContext;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaObjectNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode;
import org.eclipse.milo.opcua.sdk.server.nodes.delegates.AttributeDelegate;
import org.eclipse.milo.opcua.sdk.server.nodes.filters.AttributeFilters;
import org.eclipse.milo.opcua.sdk.server.util.SubscriptionModel;
import org.eclipse.milo.opcua.stack.core.Identifiers;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.QualifiedName;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UByte;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UShort;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class MultiMachineNameSpace extends ManagedNamespace {
    private final OpcUaServer server;
    private final NodeManager<UaNode> nodeManager;
    private final UShort namespaceIndex;
    private final List<MachineLogic> machines = new ArrayList<>();
    private final SubscriptionModel subscriptionModel;

    public MultiMachineNameSpace(OpcUaServer server) {
        super(server, "urn:virtual:plc:namespace");
        this.server = server;
        this.nodeManager = getNodeContext().getNodeManager();
        this.namespaceIndex = getNamespaceIndex();
        this.subscriptionModel = new SubscriptionModel(server, this);
    }

    /** ✅ ObjectsFolder 생성을 보장하기 위한 대기 루프 */
    private UaObjectNode ensureObjectsFolder() {
        UaObjectNode objectsFolder;

        try {
            // 기존 노드 존재 여부 확인
            objectsFolder = (UaObjectNode) getNodeContext().getNodeManager()
                    .getNode(Identifiers.ObjectsFolder)
                    .orElse(null);
        } catch (Exception e) {
            objectsFolder = null;
        }

        if (objectsFolder == null) {
            // 없으면 새로 생성
            objectsFolder = new UaObjectNode.UaObjectNodeBuilder(getNodeContext())
                    .setNodeId(Identifiers.ObjectsFolder)
                    .setBrowseName(new QualifiedName(0, "Objects"))
                    .setDisplayName(LocalizedText.english("Objects"))
                    .build();

            getNodeContext().getNodeManager().addNode(objectsFolder);
        }

        return objectsFolder;
    }

    /** ✅ MiloServerConfig에서 startup() 후 호출 */
    public void initializeNodes() {
        UaObjectNode objectsFolder = ensureObjectsFolder();
        createMachines(objectsFolder);
        startSimulation();
    }

    private void createMachines(UaObjectNode objectsFolder) {
        UaFolderNode root = new UaFolderNode(
                getNodeContext(),
                new NodeId(namespaceIndex, "VirtualMachines"),
                new QualifiedName(namespaceIndex, "VirtualMachines"),
                LocalizedText.english("VirtualMachines")
        );
        nodeManager.addNode(root);

        // 양방향 연결
        root.addReference(new Reference(root.getNodeId(), Identifiers.Organizes,
                objectsFolder.getNodeId().expanded(), false));
        objectsFolder.addReference(new Reference(objectsFolder.getNodeId(),
                Identifiers.Organizes, root.getNodeId().expanded(), true));

        // 설비 생성 및 등록
        UaFolderNode m1Folder = addMachine(root, "Machine1");
        UaFolderNode m2Folder = addMachine(root, "Machine2");

        machines.add(new Machine1Logic("Machine1", m1Folder));
        machines.add(new Machine2Logic("Machine2", m2Folder));

        machines.forEach(m -> m.setupVariables(this));
    }

    private UaFolderNode addMachine(UaFolderNode parent, String name) {
        UaFolderNode folder = new UaFolderNode(
                getNodeContext(),
                new NodeId(namespaceIndex, name),
                new QualifiedName(namespaceIndex, name),
                LocalizedText.english(name)
        );
        nodeManager.addNode(folder);
        parent.addReference(new Reference(
                parent.getNodeId(), Identifiers.Organizes,
                folder.getNodeId().expanded(), true));
        return folder;
    }

    //  MES가 OPC UA Write를 보냈을 때 → 로직 실행 ( 수정부분 )
    public void createVariable(UaFolderNode folder, String tag, NodeId type, Object initVal) {
        UaVariableNode node = UaVariableNode.builder(getNodeContext())
                .setNodeId(new NodeId(namespaceIndex, folder.getBrowseName().getName() + "." + tag))
                .setBrowseName(new QualifiedName(namespaceIndex, tag))
                .setDisplayName(LocalizedText.english(tag))
                .setDataType(type)
                .setTypeDefinition(Identifiers.BaseDataVariableType)
                .build();

        // ✅ 초기값 설정
        DataValue initValue = new DataValue(new Variant(initVal), null, null);
        node.setValue(initValue);
        node.setAccessLevel(Unsigned.ubyte(AccessLevel.CurrentRead.getValue() | AccessLevel.CurrentWrite.getValue()));
        node.setUserAccessLevel(node.getAccessLevel());
        nodeManager.addNode(node);
        folder.addReference(new Reference(folder.getNodeId(), Identifiers.Organizes, node.getNodeId().expanded(), true));

        // ✅ AccessLevel 직접 비트연산 (읽기 + 쓰기)
        UByte rwAccess = Unsigned.ubyte(
                AccessLevel.CurrentRead.getValue() | AccessLevel.CurrentWrite.getValue()
        );
        node.setAccessLevel(rwAccess);
        node.setUserAccessLevel(rwAccess);

        // ✅ Write 감지용 AttributeDelegate 설정
        node.setAttributeDelegate(new AttributeDelegate() {
            @Override
            public DataValue getValue(AttributeContext context, VariableNode variableNode) {
                DataValue val = variableNode.getValue();
                System.out.printf("[READ] %s = %s%n",
                        variableNode.getBrowseName().getName(),
                        val.getValue().getValue());
                // 읽기 요청 시 현재 값 반환
                return variableNode.getValue();
            }

            @Override
            public void setValue(AttributeContext context, VariableNode variableNode, DataValue value) {
                variableNode.setValue(value);

                String varName = variableNode.getBrowseName().getName();
                String machineName = folder.getBrowseName().getName();
                Object newValue = value.getValue().getValue();

                if ("Command".equals(varName)) {
                    System.out.printf("[MES → Milo] %s.Command = %s%n", machineName, newValue);
                    handleMesCommand(machineName, (String) newValue);
                }

                // 기본 Write 동작 유지
                variableNode.setValue(value);
            }
        });

        // Value attribute 명시적 등록
        node.setValue(new DataValue(new Variant(initVal))); // 초기값 보장

//        node.getFilterChain().addLast(
//                AttributeFilters.getValue(ctx -> {
//                    DataValue current = node.getValue();
//                    if (current == null || current.getValue() == null) {
//                        System.out.printf("[WARN] %s value null, returning default %s%n",
//                                node.getBrowseName().getName(), initVal);
//                        return new DataValue(new Variant(initVal));
//                    }
//                    System.out.printf("[READ] %s = %s%n",
//                            node.getBrowseName().getName(),
//                            current.getValue().getValue());
//                    return current;
//                })
//        );

//        node.getFilterChain().addLast(
//                AttributeFilters.setValue((ctx, value) -> {
//                    node.setValue(value);
//                    System.out.printf("[WRITE] %s = %s%n",
//                            node.getBrowseName().getName(),
//                            value.getValue().getValue());
//
//                    String varName = node.getBrowseName().getName();
//                    String machineName = folder.getBrowseName().getName();
//                    Object newValue = value.getValue().getValue();
//
//                    if ("Command".equals(varName)) {
//                        System.out.printf("[MES → Milo] %s.Command = %s%n", machineName, newValue);
//                        handleMesCommand(machineName, (String) newValue);
//                    }
//                })
//        );


        nodeManager.addNode(node);
        folder.addReference(new Reference(
                folder.getNodeId(), Identifiers.Organizes,
                node.getNodeId().expanded(), true));

        System.out.println("[DEBUG] Node created: " + folder.getBrowseName().getName() + "." + tag);
    }




    // (2) 커스텀 로직 구현부
    private void handleMesCommand(String machineName, String command) {
        switch (command) {
            case "START" -> updateMachineStatustoMES(machineName, "RUNNING");
            case "STOP"  -> updateMachineStatustoMES(machineName, "STOPPED");
            default       -> updateMachineStatustoMES(machineName, "UNKNOWN");
        }
    }

    // (3) MES로 결과 리턴 (노드 갱신)
    private void updateMachineStatustoMES(String machineName, String status) {
        UaVariableNode statusNode = (UaVariableNode) nodeManager.getNode(
                new NodeId(namespaceIndex, machineName + ".Status")).orElse(null);

        if (statusNode != null) {
            statusNode.setValue(new DataValue(new Variant(status)));
            System.out.printf("[Milo → MES] %s → %s%n", machineName, status);
        }
    }

    public void updateValue(String machineName, String tag, Object value) {
        UaVariableNode node = (UaVariableNode) nodeManager.getNode(
                new NodeId(namespaceIndex, machineName + "." + tag)
        ).orElse(null);

        if (node != null) node.setValue(new DataValue(new Variant(value)));
    }

    public void updateMachineStatus(String machineName, String status) {
        NodeId nodeId = new NodeId(namespaceIndex, machineName + ".Status");
        UaVariableNode statusNode = (UaVariableNode) nodeManager.getNode(nodeId).orElse(null);

        if (statusNode != null) {
            statusNode.setValue(new DataValue(new Variant(status)));
            System.out.printf("[Milo → MES] %s.Status = %s%n", machineName, status);
        } else {
            System.out.printf("[WARN] Status node not found for %s%n", machineName);
        }
    }

    // ✅ 현재 설비 상태(Status) 노드 값을 읽어오는 메서드
    public String getStatus(String machineName) {
        NodeId nodeId = new NodeId(namespaceIndex, machineName + ".Status");
        UaVariableNode node = (UaVariableNode) nodeManager.getNode(nodeId).orElse(null);

        if (node != null) {
            DataValue value = node.getValue();
            Object v = value.getValue().getValue();
            return v != null ? v.toString() : "UNKNOWN";
        } else {
            System.out.printf("[WARN] Status node not found for %s%n", machineName);
            return "UNKNOWN";
        }
    }


    /** ✅ 시뮬레이션은 createMachines() 이후 시작해야 한다 */
    private void startSimulation() {
        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(() -> {
            simulateMachine("Machine1.Temperature", 20 + Math.random() * 5);
        }, 5, 5, TimeUnit.SECONDS);
    }


    private void simulateMachine(String nodeName, Object newValue) {
        NodeId id = new NodeId(namespaceIndex, nodeName);

        nodeManager.getNode(id).ifPresentOrElse(
                n -> {
                    ((UaVariableNode) n).setValue(new DataValue(new Variant(newValue)));
                    System.out.printf("[SIM] Updated %s = %.2f%n", nodeName, newValue);
                },
                () -> System.out.printf("[WARN] Node not found: %s%n", nodeName)
        );
    }

    // 콜백 로깅용
    @Override
    public void onDataItemsCreated(List<DataItem> items) {
        for (DataItem item : items) {
            UaVariableNode node = (UaVariableNode) nodeManager
                    .getNode(item.getReadValueId().getNodeId())
                    .orElse(null);

            if (node != null) {
                DataValue current = node.getValue();
                if (current == null || current.getValue() == null) {
                    current = new DataValue(new Variant(0.0));
                }

                // ✅ 초기값 강제 푸시 (DataItem에 직접 설정)
                item.setValue(current);

                System.out.printf("[INIT PUSH] %s → %s%n",
                        node.getBrowseName().getName(),
                        current.getValue().getValue());
            }
        }

        System.out.println("[Namespace] onDataItemsCreated: " + items.size());
    }

    @Override
    public void onDataItemsModified(List<DataItem> items) {
        System.out.println("[Namespace] onDataItemsModified: " + items.size());
    }

    @Override
    public void onDataItemsDeleted(List<DataItem> items) { }

    @Override
    public void onMonitoringModeChanged(List<MonitoredItem> items) {
        subscriptionModel.onMonitoringModeChanged(items);
    }
}


