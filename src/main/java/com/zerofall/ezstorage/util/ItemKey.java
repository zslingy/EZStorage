package com.zerofall.ezstorage.util;

import java.util.Set;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;

/**
 * Cheap, allocation-light identity of a stored item type: (item, damage, NBT), exactly the triple that
 * {@link EZInventory#stacksEqual(ItemStack, ItemStack)} compares.
 * <p>
 * The point is the hash: {@code EZInventory} used to find a stack by walking the whole inventory and deep-comparing
 * NBT for every candidate. With this key the same lookup is a single hash probe, which is what turns insert/extract
 * from O(types) into O(1).
 * <p>
 * The hash is computed from the <em>content</em> of the tag (recursively, order-independent for compound keys) and is
 * cached in the key, so equal stacks always hash equal. Anything the hasher does not understand contributes a
 * constant; that can only ever cost a hash collision, never correctness.
 */
public final class ItemKey {

    private final Item item;
    private final int damage;
    private final NBTTagCompound tag;
    private final int hash;

    private ItemKey(ItemStack stack) {
        this.item = stack.getItem();
        this.damage = stack.getItemDamage();
        this.tag = stack.getTagCompound();
        int h = this.item.hashCode() * 31 + this.damage;
        if (this.tag != null) {
            h = h * 31 + nbtHash(this.tag);
        }
        this.hash = h;
    }

    /**
     * @return the key for the given stack, or {@code null} when the stack is empty/not an item (callers treat null as
     *         "no match").
     */
    public static ItemKey of(ItemStack stack) {
        if (stack == null || stack.getItem() == null) {
            return null;
        }
        return new ItemKey(stack);
    }

    public Item getItem() {
        return item;
    }

    public int getDamage() {
        return damage;
    }

    public NBTTagCompound getTag() {
        return tag;
    }

    @Override
    public int hashCode() {
        return this.hash;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ItemKey)) {
            return false;
        }
        ItemKey key = (ItemKey) other;
        if (this.damage != key.damage || this.item != key.item) {
            return false;
        }
        if (this.tag == null) {
            return key.tag == null;
        }
        return this.tag.equals(key.tag);
    }

    @Override
    public String toString() {
        return "ItemKey[" + this.item + ", " + this.damage + (this.tag == null ? "" : ", " + this.tag) + "]";
    }

    /**
     * Order-independent hash of an NBT value (compound keys may be iterated in any order, so only commutative mixing is
     * safe there). Lists are hashed in index order because {@code NBTTagList.equals} is order sensitive.
     */
    private static int nbtHash(NBTBase nbt) {
        if (nbt == null) {
            return 0;
        }
        byte id = nbt.getId();
        int h = id;
        switch (id) {
            case 10: {
                NBTTagCompound compound = (NBTTagCompound) nbt;
                Set<String> keys = compound.func_150296_c();
                int sum = 0;
                for (String key : keys) {
                    sum += key.hashCode() ^ nbtHash(compound.getTag(key));
                }
                return h * 31 + sum;
            }
            case 9: {
                NBTTagList list = (NBTTagList) nbt;
                int count = list.tagCount();
                int type = list.func_150303_d();
                int sum = h * 31 + count * 31 + type;
                // 1.7.10 exposes no generic indexed getter, only per-type ones. Element kinds without one
                // (lists of lists, lists of byte/short/int/long) still contribute count+type, which keeps the
                // hash consistent with equals - it can only cost a collision.
                switch (type) {
                    case 10:
                        for (int i = 0; i < count; i++) {
                            sum += (i + 1) * nbtHash(list.getCompoundTagAt(i));
                        }
                        break;
                    case 8:
                        for (int i = 0; i < count; i++) {
                            sum += (i + 1) * list.getStringTagAt(i)
                                .hashCode();
                        }
                        break;
                    case 11:
                        for (int i = 0; i < count; i++) {
                            sum += (i + 1) * java.util.Arrays.hashCode(list.func_150306_c(i));
                        }
                        break;
                    case 6:
                        for (int i = 0; i < count; i++) {
                            sum += (i + 1) * (int) Double.doubleToLongBits(list.func_150309_d(i));
                        }
                        break;
                    case 5:
                        for (int i = 0; i < count; i++) {
                            sum += (i + 1) * (int) Double.doubleToLongBits(list.func_150308_e(i));
                        }
                        break;
                    default:
                        break;
                }
                return sum;
            }
            case 1:
            case 2:
            case 3:
            case 4:
                return h * 31 + (int) ((NBTBase.NBTPrimitive) nbt).func_150291_c();
            case 5:
            case 6:
                return h * 31 + (int) Double.doubleToLongBits(((NBTBase.NBTPrimitive) nbt).func_150286_g());
            case 8:
                return h * 31 + ((NBTTagString) nbt).func_150285_a_()
                    .hashCode();
            default:
                // byte[]/int[]/unknown: constant contribution - keeps equal values equal, only risks a collision.
                return h * 31;
        }
    }
}
