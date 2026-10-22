package com.zerofall.ezstorage.tileentity;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.ISidedInventory;
import net.minecraft.item.ItemStack;

import com.zerofall.ezstorage.configuration.EZConfiguration;
import com.zerofall.ezstorage.util.EZInventory;

public class TileEntityInventoryProxy extends TileEntityMultiblock implements ISidedInventory {

    /**
     * {@link #getAccessibleSlotsFromSide(int)} used to allocate a fresh {@code int[types]} (up to 65k entries) on
     * every single call, which hoppers/pipes do several times per tick. Rebuilt only when the storage structure
     * changes.
     */
    private int[] cachedSlots;
    private int cachedStructureVersion = -1;
    private int cachedSlotCount = -1;

    @Override
    public int getSizeInventory() {
        EZInventory inventory = getInventory();
        if (inventory == null) {
            return 1;
        }
        int size = inventory.slotCount();
        if (inventory.getTotalCount() < inventory.maxItems
            && (EZConfiguration.maxItemTypes == 0 || inventory.slotCount() < EZConfiguration.maxItemTypes)) {
            size += 1;
        }
        return size;
    }

    @Override
    public ItemStack getStackInSlot(int index) {
        EZInventory inventory = getInventory();
        if (inventory != null) {
            return inventory.getStackAt(index);
        }
        return null;
    }

    @Override
    public ItemStack decrStackSize(int index, int count) {
        EZInventory inventory = getInventory();
        if (inventory == null) {
            return null;
        }
        // No sendToClients() here: the inventory records the change itself and the container syncs it once per tick.
        // Re-sending the whole inventory per transferred stack is what made pipes crawl.
        ItemStack result = inventory.getItemStackAt(index, count);
        if (result != null) {
            markStorageChanged();
        }
        return result;
    }

    @Override
    public void setInventorySlotContents(int index, ItemStack stack) {
        EZInventory inventory = getInventory();
        if (inventory == null) {
            return;
        } else if (stack == null || stack.stackSize == 0) {
            inventory.setStackAt(index, null);
        } else if (index >= inventory.slotCount()) {
            inventory.input(stack);
        } else if (isItemValidForSlot(index, stack)) {
            inventory.setStackAt(index, stack);
        }
        markStorageChanged();
    }

    /** Cheap "this block changed" notice: one description packet per tick at most, never a whole inventory push. */
    private void markStorageChanged() {
        TileEntityStorageCore core = getCore();
        if (core != null) {
            core.updateTileEntity(false);
        }
    }

    @Override
    public int getInventoryStackLimit() {
        EZInventory inventory = getInventory();
        if (inventory == null) {
            return 0;
        }
        return (int) Math.min(inventory.maxItems, Integer.MAX_VALUE);
    }

    @Override
    public boolean isUseableByPlayer(EntityPlayer player) {
        return true;
    }

    @Override
    public void openInventory() {
        // Nothing todo here
    }

    @Override
    public void closeInventory() {
        // Nothing todo here
    }

    @Override
    public boolean isItemValidForSlot(int index, ItemStack stack) {
        EZInventory inventory = getInventory();
        if (inventory == null) {
            return false;
        }
        // A new slot (one past the end) always accepts, otherwise only the slot already holding this type does.
        if (index >= inventory.slotCount()) {
            return true;
        }
        if (index < 0) {
            return false;
        }
        ItemStack group = inventory.getStackAt(index);
        return group != null && EZInventory.stacksEqual(group, stack);
    }

    @Override
    public boolean canInsertItem(int index, ItemStack stack, int direction) {
        return isItemValidForSlot(index, stack);
    }

    @Override
    public boolean canExtractItem(int index, ItemStack stack, int direction) {
        EZInventory inventory = getInventory();
        if (inventory == null) {
            return false;
        }
        ItemStack group = inventory.getStackAt(index);
        return group != null && EZInventory.stacksEqual(group, stack);
    }

    @Override
    public String getInventoryName() {
        return "proxy_port";
    }

    @Override
    public int[] getAccessibleSlotsFromSide(int p_94128_1_) {
        EZInventory inventory = getInventory();
        int version = inventory == null ? -1 : inventory.getStructureVersion();
        int size = getSizeInventory();
        if (cachedSlots == null || cachedStructureVersion != version || cachedSlotCount != size) {
            int[] slots = new int[size];
            for (int i = 0; i < size; i++) {
                slots[i] = i;
            }
            cachedSlots = slots;
            cachedStructureVersion = version;
            cachedSlotCount = size;
        }
        return cachedSlots;
    }

    @Override
    public ItemStack getStackInSlotOnClosing(int index) {
        ItemStack stack = getStackInSlot(index);
        if (stack != null) {
            setInventorySlotContents(index, null);
        }
        return stack;
    }

    @Override
    public boolean hasCustomInventoryName() {
        return false;
    }
}
