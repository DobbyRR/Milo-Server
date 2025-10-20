package com.synclab.miloserver.controller;

import com.synclab.miloserver.opcua.UaNodeManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/machine")
public class MachineController {
    private final UaNodeManager manager;

    public MachineController(UaNodeManager manager) {
        this.manager = manager;
    }

    @GetMapping("/{id}/{tag}")
    public Object read(@PathVariable String id, @PathVariable String tag) {
        return manager.readValue("Machine" + id + "." + tag);
    }

    @PostMapping("/{id}/{tag}")
    public void write(@PathVariable String id, @PathVariable String tag, @RequestParam Object value) {
        manager.writeValue("Machine" + id + "." + tag, value);
    }
}
