package com.zerofall.ezstorage.util;

import java.util.Comparator;

import net.minecraft.item.ItemStack;

public class ItemStackCountComparator implements Comparator<ItemStack> {

    @Override
    public int compare(ItemStack o1, ItemStack o2) {
        // No boxing: this runs once per comparison, i.e. millions of times for a large storage.
        return Integer.compare(o2.stackSize, o1.stackSize);
    }
}
