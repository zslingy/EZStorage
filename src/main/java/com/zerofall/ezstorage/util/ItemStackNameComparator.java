package com.zerofall.ezstorage.util;

import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Map;

import net.minecraft.item.ItemStack;

public class ItemStackNameComparator implements Comparator<ItemStack> {

    /** One sort creates one comparator, so this cache lives exactly as long as the sort it serves. */
    private final Map<ItemStack, String> names = new IdentityHashMap<ItemStack, String>();

    @Override
    public int compare(ItemStack o1, ItemStack o2) {
        return getName(o1).compareToIgnoreCase(getName(o2));
    }

    private String getName(ItemStack stack) {
        if (stack == null) {
            return "";
        }
        String cached = names.get(stack);
        if (cached != null) {
            return cached;
        }
        // getDisplayName() goes through the item's localisation every time, which is far too expensive to do once
        // per comparison on a storage with thousands of types.
        String name = stack.getDisplayName();
        if (name == null) {
            name = "";
        }
        names.put(stack, name);
        return name;
    }
}
