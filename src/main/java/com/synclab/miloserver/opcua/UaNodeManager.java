package com.synclab.miloserver.opcua;

import org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class UaNodeManager {
    private final Map<String, UaVariableNode> nodes = new ConcurrentHashMap<>();

    public void register(String name, UaVariableNode node) {
        nodes.put(name, node);
    }

    public Object readValue(String name) {
        UaVariableNode node = nodes.get(name);
        if (node == null) return null;
        return node.getValue().getValue().getValue();
    }

    public void writeValue(String name, Object value) {
        UaVariableNode node = nodes.get(name);
        if (node == null) return;
        node.setValue(new DataValue(new Variant(value)));
    }
}
