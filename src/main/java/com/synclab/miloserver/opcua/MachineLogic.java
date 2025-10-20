package com.synclab.miloserver.opcua;

import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;

public abstract class MachineLogic {

    protected final String name;
    protected final UaFolderNode folder;

    public MachineLogic(String name, UaFolderNode folder) {
        this.name = name;
        this.folder = folder;
    }

    public abstract void setupVariables(MultiMachineNameSpace ns);

    public abstract void onCommand(MultiMachineNameSpace ns, String command);

    public abstract void simulateStep(MultiMachineNameSpace ns);
}
