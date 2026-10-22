package com.zerofall.ezstorage.network.server;

import net.minecraft.entity.player.EntityPlayerMP;

import com.zerofall.ezstorage.network.client.MsgReqStorage;
import com.zerofall.ezstorage.util.EZInventory;
import com.zerofall.ezstorage.util.EZInventoryManager;

import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;

public class HandlerMsgReqStorage implements IMessageHandler<MsgReqStorage, MsgStorage> {

    @Override
    public MsgStorage onMessage(MsgReqStorage message, MessageContext ctx) {
        // Sent by hand: a big storage needs several packets and a handler can only return one.
        EntityPlayerMP player = ctx.getServerHandler().playerEntity;
        EZInventory inventory = EZInventoryManager.getInventory(message.inventoryId);
        MsgStorage.sendAll(inventory, player);
        return null;
    }
}
