package com.zerofall.ezstorage.container;

import java.time.LocalDateTime;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.InventoryBasic;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagList;

import com.zerofall.ezstorage.util.EZInventory;
import com.zerofall.ezstorage.util.EZInventoryManager;

public class ContainerStorageCore extends Container {

    public EZInventory inventory = new EZInventory();
    public LocalDateTime inventoryUpdateTimestamp = LocalDateTime.now();
    private boolean viewingInventory;

    public ContainerStorageCore(EntityPlayer player, EZInventory inventory) {
        this(player);
        this.inventory = inventory;
    }

    public ContainerStorageCore(EntityPlayer player) {
        int startingY = 18;
        int startingX = 8;
        IInventory inventory = new InventoryBasic("Storage Core", false, this.rowCount() * 9);
        for (int i = 0; i < this.rowCount(); i++) {
            for (int j = 0; j < 9; j++) {
                addSlotToContainer(new Slot(inventory, j + i * 9, startingX + j * 18, startingY + i * 18));
            }
        }

        bindPlayerInventory(player.inventory);
    }

    protected void bindPlayerInventory(InventoryPlayer inventoryPlayer) {
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 9; j++) {
                addSlotToContainer(
                    new Slot(
                        inventoryPlayer,
                        (j + i * 9) + 9,
                        playerInventoryX() + j * 18,
                        playerInventoryY() + i * 18));
            }
        }

        for (int i = 0; i < 9; i++) {
            addSlotToContainer(new Slot(inventoryPlayer, i, playerInventoryX() + i * 18, playerInventoryY() + 58));
        }
    }

    @Override
    public boolean canInteractWith(EntityPlayer playerIn) {
        return true;
    }

    // Shift clicking
    @Override
    public ItemStack transferStackInSlot(EntityPlayer playerIn, int index) {
        Slot slotObject = (Slot) inventorySlots.get(index);
        if (slotObject != null && slotObject.getHasStack()) {
            ItemStack stackInSlot = slotObject.getStack();
            slotObject.putStack(this.inventory.input(stackInSlot));
            EZInventoryManager.sendToClients(inventory);
        }
        return null;
    }

    @Override
    public ItemStack slotClick(int slotId, int clickedButton, int mode, EntityPlayer playerIn) {
        if (slotId < this.rowCount() * 9 && slotId >= 0) {
            return null;
        }
        return super.slotClick(slotId, clickedButton, mode, playerIn);
    }

    public ItemStack customSlotClick(int slotId, int clickedButton, int mode, EntityPlayer playerIn) {
        int itemIndex = slotId;
        ItemStack heldStack = playerIn.inventory.getItemStack();
        ItemStack result = null;
        boolean sendToClients = false;

        if (heldStack == null) {
            // mode=2: space key → extract all and merge into player inventory
            if (mode == 2) {
                ItemStack all = this.inventory.extractAll(itemIndex);
                if (all != null) {
                    this.mergeItemStack(all, this.rowCount() * 9, this.rowCount() * 9 + 36, true);
                    if (all.stackSize > 0) {
                        this.inventory.input(all, true);
                    }
                    sendToClients = true;
                }
                return null;
            }
            int type = 0;
            if (clickedButton == 1) {
                type = 1;
            }
            ItemStack stack = this.inventory.getItemsAt(itemIndex, type);
            if (stack != null) {
                // Shift click: merge what fits and keep the remainder in storage instead of
                // dropping it on the floor (mergeItemStack only reports partial success).
                if (clickedButton == 0 && mode == 1) {
                    this.mergeItemStack(stack, this.rowCount() * 9, this.rowCount() * 9 + 36, true);
                    if (stack.stackSize > 0) {
                        this.inventory.input(stack, true);
                    }
                } else {
                    playerIn.inventory.setItemStack(stack);
                }
                sendToClients = true;
                result = stack;
            }
        } else {
            playerIn.inventory.setItemStack(this.inventory.input(heldStack));
            sendToClients = true;
        }

        if (sendToClients) {
            EZInventoryManager.sendToClients(inventory);
        }

        return result;
    }

    /** NEI bookmark autocraft (Shift+C): pull the requested items out of storage into the player's inventory. */
    public void pullBookmarkItems(NBTTagList requested, EntityPlayer playerIn) {
        if (requested == null) {
            return;
        }
        boolean changed = false;
        for (int i = 0; i < requested.tagCount(); i++) {
            ItemStack wanted = ItemStack.loadItemStackFromNBT(requested.getCompoundTagAt(i));
            if (wanted == null) {
                continue;
            }
            int index = this.inventory.getIndexOf(wanted);
            if (index < 0) {
                continue;
            }
            ItemStack extracted = this.inventory.getItemStackAt(index, wanted.stackSize);
            if (extracted == null) {
                continue;
            }
            this.mergeItemStack(extracted, this.rowCount() * 9, this.rowCount() * 9 + 36, true);
            if (extracted.stackSize > 0) {
                this.inventory.input(extracted, true);
            }
            changed = true;
        }
        if (changed) {
            EZInventoryManager.sendToClients(inventory);
        }
    }

    protected int playerInventoryX() {
        return 8;
    }

    protected int playerInventoryY() {
        return 140;
    }

    protected int rowCount() {
        return 6;
    }

    /**
     * Runs once per tick on the server for every open container. Registers the viewer (so the inventory starts queueing
     * deltas) and flushes them - this is what catches changes made outside of a container click, e.g. by a pipe or by
     * the AE2 integration.
     */
    @Override
    public void detectAndSendChanges() {
        super.detectAndSendChanges();
        if (inventory == null) {
            return;
        }
        if (!viewingInventory) {
            viewingInventory = true;
            inventory.addSyncViewer();
        }
        EZInventoryManager.sendToClients(inventory);
    }

    @Override
    public void onContainerClosed(EntityPlayer playerIn) {
        super.onContainerClosed(playerIn);
        if (!playerIn.worldObj.isRemote) {
            if (viewingInventory) {
                viewingInventory = false;
                inventory.removeSyncViewer();
            }
            this.inventory.sort();
            // Sorting reorders the list, which the deltas cannot express - but only the remaining viewers care.
            if (this.inventory.hasSyncViewers()) {
                EZInventoryManager.sendSnapshot(inventory);
            }
        }
    }

    /** Move the player's hotbar (0-8) or main inventory (9-35) into storage. */
    public void importPlayerInventory(EntityPlayer player, boolean hotbarOnly) {
        InventoryPlayer inv = player.inventory;
        int start = hotbarOnly ? 0 : 9;
        int end = hotbarOnly ? 9 : 36;
        for (int i = start; i < end; i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (stack != null) {
                ItemStack remainder = this.inventory.input(stack);
                inv.setInventorySlotContents(i, remainder);
            }
        }
        EZInventoryManager.sendToClients(inventory);
    }

    public void dropItem(int itemIndex, int amount, EntityPlayer player) {
        if (itemIndex >= this.inventory.slotCount()) {
            return;
        }
        ItemStack toDrop;
        if (amount <= 0) {
            toDrop = this.inventory.extractAll(itemIndex);
        } else if (amount == 1) {
            toDrop = this.inventory.extractOne(itemIndex);
        } else {
            toDrop = this.inventory.extractStack(itemIndex);
        }
        if (toDrop != null && toDrop.stackSize > 0) {
            int maxStack = toDrop.getMaxStackSize();
            while (toDrop.stackSize > 0) {
                int dropSize = Math.min(toDrop.stackSize, maxStack);
                ItemStack dropStack = toDrop.copy();
                dropStack.stackSize = dropSize;
                toDrop.stackSize -= dropSize;
                player.dropPlayerItemWithRandomChoice(dropStack, false);
            }
            EZInventoryManager.sendToClients(inventory);
        }
    }
}
