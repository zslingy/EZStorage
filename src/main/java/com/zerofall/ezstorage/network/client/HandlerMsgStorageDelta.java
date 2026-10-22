package com.zerofall.ezstorage.network.client;

import java.time.LocalDateTime;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;

import com.zerofall.ezstorage.EZStorage;
import com.zerofall.ezstorage.container.ContainerStorageCore;
import com.zerofall.ezstorage.network.server.MsgStorageDelta;
import com.zerofall.ezstorage.util.EZInventory;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;

public class HandlerMsgStorageDelta implements IMessageHandler<MsgStorageDelta, IMessage> {

    @Override
    public IMessage onMessage(MsgStorageDelta message, MessageContext ctx) {
        // Deltas mutate the shared inventory list, so they have to run on the client thread - the old full-inventory
        // packet did this work inline on the netty thread.
        Minecraft.getMinecraft()
            .func_152344_a(() -> apply(message));
        return null;
    }

    private static void apply(MsgStorageDelta message) {
        EntityPlayer player = Minecraft.getMinecraft().thePlayer;
        if (player == null) {
            return;
        }
        Container container = player.openContainer;
        if (!(container instanceof ContainerStorageCore storageContainer)) {
            return;
        }
        EZInventory inventory = storageContainer.inventory;
        if (inventory == null || message.storageId == null || !message.storageId.equals(inventory.id)) {
            return;
        }
        boolean changed = false;
        for (EZInventory.SyncEntry entry : message.entries) {
            changed |= inventory.applySync(entry.slotId, entry.count, entry.stack);
        }
        if (changed || !message.entries.isEmpty()) {
            storageContainer.inventoryUpdateTimestamp = LocalDateTime.now();
        }
        if (inventory.consumeSyncMiss()) {
            // A delta referenced a slot this client never saw - ask for the whole inventory once.
            EZStorage.instance.network.sendToServer(new MsgReqStorage(inventory.id));
        }
    }
}
