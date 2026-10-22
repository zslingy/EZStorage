package com.zerofall.ezstorage.util;

import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Map;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import cpw.mods.fml.common.registry.GameData;

public class ItemStackModComparator implements Comparator<ItemStack> {

    /** One sort creates one comparator, so this cache lives exactly as long as the sort it serves. */
    private final Map<Item, String> modIds = new IdentityHashMap<Item, String>();

    @Override
    public int compare(ItemStack o1, ItemStack o2) {
        String m1 = getModId(o1);
        String m2 = getModId(o2);
        return m1.compareToIgnoreCase(m2);
    }

    private String getModId(ItemStack stack) {
        if (stack == null) {
            return "minecraft";
        }
        Item item = stack.getItem();
        String cached = modIds.get(item);
        if (cached != null) {
            return cached;
        }
        String name = GameData.getItemRegistry()
            .getNameForObject(item);
        String modId = "minecraft";
        if (name != null) {
            int colon = name.indexOf(':');
            if (colon > 0) {
                modId = name.substring(0, colon);
            }
        }
        modIds.put(item, modId);
        return modId;
    }
}
