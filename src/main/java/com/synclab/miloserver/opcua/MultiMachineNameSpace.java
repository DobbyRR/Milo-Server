package com.synclab.miloserver.opcua;

import org.eclipse.milo.opcua.sdk.server.OpcUaServer;
import org.eclipse.milo.opcua.sdk.server.api.ManagedNamespace;
import org.eclipse.milo.opcua.sdk.server.api.DataItem;
import org.eclipse.milo.opcua.sdk.server.api.MonitoredItem;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode;
import org.eclipse.milo.opcua.sdk.server.util.SubscriptionModel;
import org.eclipse.milo.opcua.stack.core.Identifiers;
import org.eclipse.milo.opcua.stack.core.types.builtin.*;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UShort;
import org.eclipse.milo.opcua.sdk.core.Reference;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

public class MultiMachineNameSpace extends ManagedNamespace {

    private static MultiMachineNameSpace instance;
    private final OpcUaServer server;
    private final SubscriptionModel subscriptionModel;
    private final AtomicInteger nodeCounter = new AtomicInteger(1);
    private final UaFolderNode rootFolder;

    public MultiMachineNameSpace(OpcUaServer server, String namespaceUri) {
        super(server, namespaceUri);
        this.server = server;
        this.subscriptionModel = new SubscriptionModel(server, this);
        instance = this;

        UShort nsIdx = getNamespaceIndex();

        // ✅ "Machines" 루트 폴더 생성
        rootFolder = new UaFolderNode(
                getNodeContext(),
                new NodeId(nsIdx, "Machines"),
                new QualifiedName(nsIdx, "Machines"),
                LocalizedText.english("Machines")
        );

        // Node 등록
        getNodeContext().getNodeManager().addNode(rootFolder);

        // ObjectsFolder와 Organizes 관계로 연결
        rootFolder.addReference(new Reference(
                rootFolder.getNodeId(),
                Identifiers.Organizes,
                Identifiers.ObjectsFolder.expanded(),
                false
        ));
    }

    /** 루트 폴더 반환 */
    public UaFolderNode getRootFolder() {
        return rootFolder;
    }

    /**
     * 서버 시작 시 호출되어 노드 트리 구조를 초기화하는 메서드
     */
    public void initializeNodes() {
        System.out.println("[DEBUG] AddressSpaceManager class: "
                + getServer().getAddressSpaceManager().getClass().getName());

        UShort nsIndex = getNamespaceIndex();

        // ✅ NodeContext 내부 NodeManager에서 ObjectsFolder 탐색
        UaFolderNode objectsFolder = getNodeContext()
                .getNodeManager()
                .getNode(Identifiers.ObjectsFolder)
                .filter(n -> n instanceof UaFolderNode)
                .map(n -> (UaFolderNode) n)
                .orElseThrow(() ->
                        new IllegalStateException("ObjectsFolder not found in NodeContext."));

        // ✅ 공장 관련 노드 생성
        addVariableNode(objectsFolder, "Factory.Status", "RUNNING");
        addVariableNode(objectsFolder, "Factory.Throughput", 120);

//        if (objectsFolder == null) {
//            throw new IllegalStateException("ObjectsFolder not found in NodeContext.");
//        }

        // ✅ 공장 관련 노드 생성 (예시)
        addVariableNode(objectsFolder, "Factory.Status", "RUNNING");
        addVariableNode(objectsFolder, "Factory.Throughput", 120);

        System.out.println("[MultiMachineNameSpace] ObjectsFolder initialized successfully.");
        System.out.println("[DEBUG] namespace index: " + getNamespaceIndex());
    }

    /** 변수 노드 생성 */
    public UaVariableNode addVariableNode(UaFolderNode parent, String name, Object initialValue) {
        NodeId nodeId = new NodeId(getNamespaceIndex(), nodeCounter.getAndIncrement());

        UaVariableNode node = UaVariableNode.builder(getNodeContext())
                .setNodeId(nodeId)
                .setBrowseName(new QualifiedName(getNamespaceIndex(), name))
                .setDisplayName(LocalizedText.english(name))
                .setDataType(Identifiers.BaseDataType)
                .setValue(new DataValue(new Variant(initialValue)))
                .build();

        getNodeContext().getNodeManager().addNode(node);

        parent.addReference(new Reference(
                parent.getNodeId(),
                Identifiers.Organizes,
                node.getNodeId().expanded(),
                false
        ));

        return node;
    }

    /** 값 갱신 및 구독자 알림 */
    public void updateValue(UaVariableNode node, Object newValue) {
        node.setValue(new DataValue(new Variant(newValue)));
    }

    /* ---------- MonitoredItemServices 구현 ---------- */
    @Override
    public void onDataItemsCreated(List<DataItem> items) {
        subscriptionModel.onDataItemsCreated(items);
    }

    @Override
    public void onDataItemsModified(List<DataItem> items) {
        subscriptionModel.onDataItemsModified(items);
    }

    @Override
    public void onDataItemsDeleted(List<DataItem> items) {
        subscriptionModel.onDataItemsDeleted(items);
    }

    @Override
    public void onMonitoringModeChanged(List<MonitoredItem> items) {
        subscriptionModel.onMonitoringModeChanged(items);
    }
}


