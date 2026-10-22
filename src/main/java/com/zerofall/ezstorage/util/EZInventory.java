package com.zerofall.ezstorage.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.zerofall.ezstorage.EZStorage;
import com.zerofall.ezstorage.configuration.EZConfiguration;

/**
 * The storage of one storage core: exactly one {@link ItemStack} per stored item type, whose {@code stackSize} is the
 * total amount of that type.
 * <p>
 * Two things are maintained on top of the plain list and must never be edited behind this class' back:
 * <ul>
 * <li>{@link #entries} - {@link ItemKey} to stack, so lookups/merges are one hash probe instead of a full scan with
 * deep NBT comparison.</li>
 * <li>{@link #cachedTotal} - the sum of every stack size, so {@link #getTotalCount()} is free.</li>
 * </ul>
 * Every mutation also records its new absolute count per slot when {@link #setTrackChanges(boolean) change tracking}
 * is on (server side); {@code ContainerStorageCore} flushes those once per tick as a delta packet instead of shipping
 * the whole inventory on every click.
 */
public class EZInventory {

    private boolean hasChanges;
    public List<ItemStack> inventory;
    public long maxItems = 0;
    public String id;
    public boolean disabled;
    public ItemStack[] craftMatrix;

    /** {@link ItemKey} -> the canonical stack of that type (the very object held by {@link #inventory}). */
    private final Map<ItemKey, ItemStack> entries = new HashMap<ItemKey, ItemStack>();
    /** Sum of all {@code stackSize} in {@link #inventory}. */
    private long cachedTotal;
    /** Bumped whenever the set of item types changes (not for mere count changes). */
    private int structureVersion;
    /** Bumped on every mutation, count changes included. */
    private int contentVersion;

    private int nextSlotId = 1;
    private final Map<ItemKey, Integer> slotIdOf = new HashMap<ItemKey, Integer>();
    private final Map<Integer, ItemStack> bySlotId = new HashMap<Integer, ItemStack>();

    private boolean trackChanges;
    private int syncViewers;
    private boolean syncMissed;
    private final Map<Integer, PendingOp> pending = new LinkedHashMap<Integer, PendingOp>();

    /** {@link Item} -> every stack of that item, rebuilt lazily whenever {@link #structureVersion} moved on. */
    private Map<Item, List<ItemStack>> byItem;
    /** The {@link #structureVersion} {@link #byItem} was built for. */
    private int byItemVersion = -1;

    public EZInventory() {
        // NEI's autocraft engine touches this from its own thread while the client thread renders/filters it.
        // Callers that iterate (not just get/set/remove) must synchronize on `inventory` for the loop's duration.
        inventory = Collections.synchronizedList(new ArrayList<ItemStack>());
    }

    public boolean getHasChanges() {
        return hasChanges;
    }

    public void setHasChanges() {
        hasChanges = true;
    }

    public void resetHasChanges() {
        hasChanges = false;
    }

    /**
     * While enabled (server side only), every mutation queues the item's new absolute count for the next delta packet.
     * The client never enables this: it receives deltas instead of producing them.
     */
    public void setTrackChanges(boolean trackChanges) {
        synchronized (inventory) {
            this.trackChanges = trackChanges;
            if (!trackChanges) {
                pending.clear();
            }
        }
    }

    /**
     * Registers an open container as a delta consumer. Tracking is reference counted, otherwise closing one of several
     * terminals would stop the deltas for the others.
     */
    public void addSyncViewer() {
        synchronized (inventory) {
            syncViewers++;
            trackChanges = true;
        }
    }

    public boolean hasSyncViewers() {
        synchronized (inventory) {
            return syncViewers > 0;
        }
    }

    /** Returns and clears whether a delta could not be applied, i.e. the caller should request a full snapshot. */
    public boolean consumeSyncMiss() {
        synchronized (inventory) {
            boolean missed = syncMissed;
            syncMissed = false;
            return missed;
        }
    }

    public void removeSyncViewer() {
        synchronized (inventory) {
            if (syncViewers > 0) {
                syncViewers--;
            }
            if (syncViewers == 0) {
                trackChanges = false;
                pending.clear();
            }
        }
    }

    public int getStructureVersion() {
        return structureVersion;
    }

    public int getContentVersion() {
        return contentVersion;
    }

    public ItemStack input(ItemStack itemStack) {
        return input(itemStack, false);
    }

    public ItemStack input(ItemStack itemStack, boolean force) {
        synchronized (inventory) {
            // Inventory is full
            long maxPossible = force ? Long.MAX_VALUE : maxItems;
            if (cachedTotal >= maxPossible) {
                return itemStack;
            }
            long space = maxPossible - cachedTotal;
            // Only part of the stack can fit
            int amount = (int) Math.min(space, (long) itemStack.stackSize);
            if (amount <= 0) {
                return itemStack;
            }
            ItemStack stack = mergeStack(itemStack, amount);
            setHasChanges();
            return stack;
        }
    }

    public ItemStack simulateInput(ItemStack itemStack) {
        synchronized (inventory) {
            if (cachedTotal >= maxItems) {
                return itemStack;
            }
            long space = maxItems - cachedTotal;
            int amount = (int) Math.min(space, (long) itemStack.stackSize);
            ItemStack remainder = itemStack.copy();
            remainder.stackSize -= amount;
            if (remainder.stackSize <= 0) {
                return null;
            }
            return remainder;
        }
    }

    public void sort() {
        synchronized (inventory) {
            Collections.sort(this.inventory, new ItemStackCountComparator());
        }
        setHasChanges();
    }

    private ItemStack mergeStack(ItemStack itemStack, int amount) {
        ItemKey key = ItemKey.of(itemStack);
        ItemStack group = key == null ? null : entries.get(key);
        if (group != null) {
            group.stackSize += amount;
            cachedTotal += amount;
            contentVersion++;
            recordCount(group);
            setHasChanges();
        } else {
            // Add new group, if needed
            if (EZConfiguration.maxItemTypes != 0 && slotCount() > EZConfiguration.maxItemTypes) {
                return itemStack;
            }
            ItemStack copy = itemStack.copy();
            copy.stackSize = amount;
            addEntry(copy);
            setHasChanges();
        }

        // Adjust input/return stack
        itemStack.stackSize -= amount;
        if (itemStack.stackSize <= 0) {
            return null;
        } else {
            return itemStack;
        }
    }

    private void addEntry(ItemStack copy) {
        ItemKey key = ItemKey.of(copy);
        if (key == null) {
            return;
        }
        ItemStack previous = entries.get(key);
        if (previous != null) {
            // Defensive: never keep two entries of the same type.
            removeEntry(previous, true);
        }
        inventory.add(copy);
        entries.put(key, copy);
        int slotId = nextSlotId++;
        slotIdOf.put(key, Integer.valueOf(slotId));
        bySlotId.put(Integer.valueOf(slotId), copy);
        cachedTotal += copy.stackSize;
        structureVersion++;
        contentVersion++;
        recordAdded(copy, slotId);
    }

    /**
     * Removes the whole entry holding {@code group}.
     *
     * @param announce whether the sync layer should tell clients this slot is gone
     */
    private void removeEntry(ItemStack group, boolean announce) {
        if (group == null) {
            return;
        }
        int position = inventory.indexOf(group);
        if (position >= 0) {
            inventory.remove(position);
        }
        ItemKey key = ItemKey.of(group);
        if (key != null) {
            entries.remove(key);
            Integer slotId = slotIdOf.remove(key);
            if (slotId != null) {
                bySlotId.remove(slotId);
                if (announce && trackChanges) {
                    PendingOp op = pendingOp(slotId.intValue());
                    op.count = 0L;
                    op.needsStack = false;
                    op.stack = null;
                }
            }
        }
        cachedTotal -= group.stackSize;
        structureVersion++;
        contentVersion++;
    }

    /** Decrements {@code group} by {@code size}, dropping the entry when it runs out. */
    private void take(ItemStack group, int size) {
        group.stackSize -= size;
        cachedTotal -= size;
        contentVersion++;
        recordCount(group);
        if (group.stackSize <= 0) {
            removeEntry(group, false);
        }
    }

    /**
     * Removes {@code size} items from {@code group}, which must be one of this inventory's live entries. Unlike
     * mutating the list directly this keeps the index, the cached total and the delta queue in sync.
     */
    public ItemStack takeFrom(ItemStack group, int size) {
        synchronized (inventory) {
            if (group == null || size <= 0 || group.stackSize < size) {
                return null;
            }
            ItemStack stack = group.copy();
            stack.stackSize = size;
            take(group, size);
            setHasChanges();
            return stack;
        }
    }

    // Type: 0= full stack, 1= half stack, 2= single
    public ItemStack getItemsAt(int index, int type) {
        synchronized (inventory) {
            if (index >= inventory.size()) {
                return null;
            }
            ItemStack group = inventory.get(index);
            ItemStack stack = group.copy();
            int size = Math.min(stack.getMaxStackSize(), group.stackSize);
            if (size > 1) {
                if (type == 1) {
                    size = size / 2;
                } else if (type == 2) {
                    size = 1;
                }
            }
            stack.stackSize = size;
            take(group, size);
            setHasChanges();
            return stack;
        }
    }

    public ItemStack getItemStackAt(int index, int size) {
        synchronized (inventory) {
            if (index >= inventory.size()) {
                return null;
            }
            ItemStack group = inventory.get(index);
            ItemStack stack = group.copy();
            if (size > group.stackSize) {
                size = group.stackSize;
            }
            stack.stackSize = size;
            take(group, size);
            setHasChanges();
            return stack;
        }
    }

    public ItemStack simulateRemove(int index, int size) {
        synchronized (inventory) {
            if (index >= inventory.size()) {
                return null;
            }
            ItemStack group = inventory.get(index);
            ItemStack stack = group.copy();
            if (size > group.stackSize) {
                size = group.stackSize;
            }
            stack.stackSize = size;
            return stack;
        }
    }

    public ItemStack getItems(ItemStack[] itemStacks) {
        synchronized (inventory) {
            Map<ItemKey, ItemStack> wanted = new HashMap<ItemKey, ItemStack>();
            for (ItemStack itemStack : itemStacks) {
                ItemKey key = ItemKey.of(itemStack);
                if (key != null && !wanted.containsKey(key)) {
                    wanted.put(key, itemStack);
                }
            }
            for (ItemStack group : inventory) {
                ItemStack itemStack = wanted.get(ItemKey.of(group));
                if (itemStack == null) {
                    continue;
                }
                if (group.stackSize >= itemStack.stackSize) {
                    ItemStack stack = group.copy();
                    stack.stackSize = itemStack.stackSize;
                    take(group, itemStack.stackSize);
                    setHasChanges();
                    return stack;
                }
                return null;
            }
        }
        return null;
    }

    public ItemStack extractAll(int index) {
        synchronized (inventory) {
            if (index >= inventory.size()) {
                return null;
            }
            ItemStack group = inventory.get(index);
            ItemStack result = group.copy();
            removeEntry(group, true);
            setHasChanges();
            return result;
        }
    }

    public ItemStack extractOne(int index) {
        return getItemsAt(index, 2);
    }

    public ItemStack extractStack(int index) {
        return getItemsAt(index, 0);
    }

    public int getIndexOf(ItemStack itemStack) {
        synchronized (inventory) {
            ItemKey key = ItemKey.of(itemStack);
            if (key == null) {
                return -1;
            }
            ItemStack group = entries.get(key);
            if (group == null) {
                return -1;
            }
            return inventory.indexOf(group);
        }
    }

    /** The stack stored at {@code index}, or {@code null} when out of range. */
    public ItemStack getStackAt(int index) {
        synchronized (inventory) {
            if (index < 0 || index >= inventory.size()) {
                return null;
            }
            return inventory.get(index);
        }
    }

    /** Replaces the entry at {@code index}; {@code null} removes it. */
    public void setStackAt(int index, ItemStack stack) {
        synchronized (inventory) {
            if (index < 0 || index >= inventory.size()) {
                return;
            }
            ItemStack previous = inventory.get(index);
            if (stack == null) {
                removeEntry(previous, true);
                setHasChanges();
                return;
            }
            removeEntry(previous, true);
            ItemStack copy = stack.copy();
            if (copy.stackSize > 0) {
                addEntry(copy);
            }
            setHasChanges();
        }
    }

    public int slotCount() {
        return inventory.size();
    }

    /**
     * Every stored stack whose {@link Item} is the given one, or {@code null} when nothing of that item is stored.
     * <p>
     * Refilling a crafting grid asks "is there a stack of this item holding at least N" for every grid slot on every
     * craft iteration; answering that with a full scan plus an NBT comparison per entry was the dominant cost of batch
     * crafting. Any candidate such a scan could accept necessarily has the same {@link Item} - both the ore dictionary
     * check and {@link #stacksEqual(ItemStack, ItemStack)} compare that first - so bucketing by item keeps the
     * candidate set complete while turning the scan into a single hash lookup.
     * <p>
     * The returned list is the live bucket: callers must not modify it, and must stop iterating it as soon as they
     * mutate this inventory (a removal takes the entry out of the bucket).
     */
    public List<ItemStack> getStacksForItem(Item item) {
        if (item == null) {
            return null;
        }
        synchronized (inventory) {
            if (byItem == null || byItemVersion != structureVersion) {
                Map<Item, List<ItemStack>> rebuilt = new HashMap<Item, List<ItemStack>>(inventory.size() * 2);
                for (int i = 0; i < inventory.size(); i++) {
                    ItemStack group = inventory.get(i);
                    if (group == null || group.getItem() == null) {
                        continue;
                    }
                    List<ItemStack> bucket = rebuilt.get(group.getItem());
                    if (bucket == null) {
                        bucket = new ArrayList<ItemStack>(2);
                        rebuilt.put(group.getItem(), bucket);
                    }
                    bucket.add(group);
                }
                byItem = rebuilt;
                byItemVersion = structureVersion;
            }
            return byItem.get(item);
        }
    }

    public static boolean stacksEqual(ItemStack stack1, ItemStack stack2) {
        if (stack1 == null && stack2 == null) {
            return true;
        }
        if (stack1 == null || stack2 == null) {
            return false;
        }
        if (stack1.getItem() != stack2.getItem()) {
            return false;
        }
        if (stack1.getItemDamage() != stack2.getItemDamage()) {
            return false;
        }
        NBTTagCompound stack1Tag = stack1.getTagCompound();
        NBTTagCompound stack2Tag = stack2.getTagCompound();
        if (stack1Tag == null && stack2Tag == null) {
            return true;
        }
        if (stack1Tag == null || stack2Tag == null) {
            return false;
        }
        if (stack1Tag.equals(stack2Tag)) {
            return true;
        }
        return false;
    }

    public long getTotalCount() {
        // Cached; written under the list's monitor. A plain read keeps this free on the render path.
        return cachedTotal;
    }

    @Override
    public String toString() {
        return inventory.toString();
    }

    // ------------------------------------------------------------------
    // sync
    // ------------------------------------------------------------------

    public boolean hasPendingSync() {
        synchronized (inventory) {
            return !pending.isEmpty();
        }
    }

    /**
     * Returns the slots whose count changed since the last call (absolute counts, newest state), in slot id order, and
     * clears the queue. Entries created since the last flush carry the full stack so the client can create them.
     */
    public List<SyncEntry> drainSync() {
        synchronized (inventory) {
            if (pending.isEmpty()) {
                return null;
            }
            List<Integer> ids = new ArrayList<Integer>(pending.keySet());
            Collections.sort(ids);
            List<SyncEntry> out = new ArrayList<SyncEntry>(ids.size());
            for (Integer id : ids) {
                PendingOp op = pending.get(id);
                out.add(new SyncEntry(id.intValue(), op.count, op.needsStack ? op.stack : null));
            }
            pending.clear();
            return out;
        }
    }

    /**
     * Client side: applies one delta entry.
     *
     * @return true when the set of item types changed (the view has to be rebuilt), false for a mere count change
     */
    public boolean applySync(int slotId, long count, ItemStack stack) {
        synchronized (inventory) {
            ItemStack existing = bySlotId.get(Integer.valueOf(slotId));
            if (count <= 0L) {
                if (existing == null) {
                    return false;
                }
                removeEntry(existing, false);
                return true;
            }
            long clamped = Math.min(count, Integer.MAX_VALUE);
            if (existing == null) {
                if (stack == null) {
                    // A delta for a slot this side has never seen: ask for a fresh snapshot instead of guessing.
                    syncMissed = true;
                    return false;
                }
                ItemKey key = ItemKey.of(stack);
                if (key == null) {
                    return false;
                }
                ItemStack stale = entries.get(key);
                if (stale != null) {
                    removeEntry(stale, false);
                }
                ItemStack copy = stack.copy();
                copy.stackSize = (int) clamped;
                inventory.add(copy);
                entries.put(key, copy);
                slotIdOf.put(key, Integer.valueOf(slotId));
                bySlotId.put(Integer.valueOf(slotId), copy);
                cachedTotal += clamped;
                structureVersion++;
                contentVersion++;
                return true;
            }
            long previous = existing.stackSize;
            if (previous == clamped) {
                return false;
            }
            existing.stackSize = (int) clamped;
            cachedTotal += clamped - previous;
            contentVersion++;
            return false;
        }
    }

    private PendingOp pendingOp(int slotId) {
        Integer boxed = Integer.valueOf(slotId);
        PendingOp op = pending.get(boxed);
        if (op == null) {
            op = new PendingOp();
            pending.put(boxed, op);
        }
        return op;
    }

    private void recordCount(ItemStack group) {
        if (!trackChanges) {
            return;
        }
        ItemKey key = ItemKey.of(group);
        if (key == null) {
            return;
        }
        Integer slotId = slotIdOf.get(key);
        if (slotId == null) {
            return;
        }
        PendingOp op = pendingOp(slotId.intValue());
        op.count = group.stackSize;
        if (op.needsStack) {
            op.stack = group.copy();
        }
    }

    private void recordAdded(ItemStack group, int slotId) {
        if (!trackChanges) {
            return;
        }
        PendingOp op = pendingOp(slotId);
        op.count = group.stackSize;
        op.needsStack = true;
        op.stack = group.copy();
    }

    public static SyncEntry syncEntry(int slotId, long count, ItemStack stack) {
        return new SyncEntry(slotId, count, stack);
    }

    /** One queued delta: the absolute count of {@code slotId}, plus its content when the client may not know it. */
    public static final class SyncEntry {

        public final int slotId;
        public final long count;
        public final ItemStack stack;

        SyncEntry(int slotId, long count, ItemStack stack) {
            this.slotId = slotId;
            this.count = count;
            this.stack = stack;
        }
    }

    private static final class PendingOp {

        long count;
        boolean needsStack;
        ItemStack stack;
    }

    // ------------------------------------------------------------------
    // nbt
    // ------------------------------------------------------------------

    public void writeToNBT(NBTTagCompound tag) {
        NBTTagList nbttaglist = new NBTTagList();
        synchronized (inventory) {
            for (int i = 0; i < this.slotCount(); ++i) {
                ItemStack group = this.inventory.get(i);
                if (group != null && group.stackSize > 0) {
                    NBTTagCompound nbttagcompound1 = new NBTTagCompound();
                    group.writeToNBT(nbttagcompound1);
                    nbttagcompound1.setInteger("InternalCount", group.stackSize);
                    nbttaglist.appendTag(nbttagcompound1);
                }
            }
        }
        tag.setTag("Internal", nbttaglist);
        tag.setLong("InternalMax", this.maxItems);
        tag.setBoolean("isDisabled", this.disabled);

        if (this.craftMatrix != null) {
            NBTTagList gridList = new NBTTagList();
            for (int i = 0; i < 9; i++) {
                NBTTagCompound slotTag = new NBTTagCompound();
                slotTag.setByte("Slot", (byte) i);
                if (this.craftMatrix[i] != null) {
                    this.craftMatrix[i].writeToNBT(slotTag);
                }
                gridList.appendTag(slotTag);
            }
            tag.setTag("CraftMatrix", gridList);
        }
    }

    /**
     * Like {@link #writeToNBT} plus the slot ids of every written entry, so a client can apply deltas that address
     * slots instead of shipping the whole inventory again on the next change. The on-disk format is unaffected.
     */
    public void writeToPacketNBT(NBTTagCompound tag) {
        writeToNBT(tag);
        int written = tag.getTagList("Internal", 10)
            .tagCount();
        int[] slots = new int[written];
        int index = 0;
        synchronized (inventory) {
            for (int i = 0; i < this.inventory.size() && index < written; i++) {
                ItemStack group = this.inventory.get(i);
                if (group == null || group.stackSize <= 0) {
                    continue;
                }
                ItemKey key = ItemKey.of(group);
                Integer slotId = key == null ? null : slotIdOf.get(key);
                slots[index++] = slotId == null ? -1 : slotId.intValue();
            }
        }
        tag.setIntArray("InternalSlots", slots);
        tag.setInteger("NextSlotId", nextSlotId);
    }

    public void readFromNBT(NBTTagCompound tag) {
        NBTTagList nbttaglist = tag.getTagList("Internal", 10);

        if (nbttaglist != null) {
            // Build off to the side and swap the reference at the end, so a reader on another thread never
            // sees this list half-populated (and never falls back to a plain, non-synchronized ArrayList).
            List<ItemStack> loaded = new ArrayList<ItemStack>();
            for (int i = 0; i < nbttaglist.tagCount(); ++i) {
                NBTTagCompound nbttagcompound1 = nbttaglist.getCompoundTagAt(i);
                ItemStack stack = ItemStack.loadItemStackFromNBT(nbttagcompound1);
                if (stack == null) {
                    EZStorage.instance.LOG.warn("An ItemStack loaded from NBT was null.");
                    continue;
                }
                if (nbttagcompound1.hasKey("InternalCount", 3)) {
                    stack.stackSize = nbttagcompound1.getInteger("InternalCount");
                } else if (nbttagcompound1.hasKey("InternalCount", 4)) {
                    stack.stackSize = (int) nbttagcompound1.getLong("InternalCount");
                }
                loaded.add(stack);
            }
            rebuildIndex(loaded);
        }
        this.maxItems = tag.getLong("InternalMax");
        this.disabled = tag.getBoolean("isDisabled");

        if (tag.hasKey("CraftMatrix", 9)) {
            NBTTagList gridList = tag.getTagList("CraftMatrix", 10);
            this.craftMatrix = new ItemStack[9];
            for (int i = 0; i < gridList.tagCount(); i++) {
                NBTTagCompound slotTag = gridList.getCompoundTagAt(i);
                byte slotIndex = slotTag.getByte("Slot");
                if (slotIndex >= 0 && slotIndex < 9) {
                    this.craftMatrix[slotIndex] = ItemStack.loadItemStackFromNBT(slotTag);
                }
            }
        }
    }

    /**
     * Rebuilds {@link #entries}, {@link #cachedTotal} and the slot ids from the given list, and only then publishes
     * it as {@link #inventory} - readers never see a new list paired with a stale index.
     */
    private void rebuildIndex(List<ItemStack> loaded) {
        Map<ItemKey, ItemStack> newEntries = new HashMap<ItemKey, ItemStack>(loaded.size() * 2);
        Map<ItemKey, Integer> newSlotIdOf = new HashMap<ItemKey, Integer>(loaded.size() * 2);
        Map<Integer, ItemStack> newBySlotId = new HashMap<Integer, ItemStack>(loaded.size() * 2);
        long total = 0L;
        int next = 1;
        for (int i = 0; i < loaded.size(); i++) {
            ItemStack group = loaded.get(i);
            if (group == null) {
                continue;
            }
            ItemKey key = ItemKey.of(group);
            if (key == null) {
                continue;
            }
            newEntries.put(key, group);
            newSlotIdOf.put(key, Integer.valueOf(next));
            newBySlotId.put(Integer.valueOf(next), group);
            next++;
            total += group.stackSize;
        }
        synchronized (this.inventory) {
            pending.clear();
            entries.clear();
            entries.putAll(newEntries);
            slotIdOf.clear();
            slotIdOf.putAll(newSlotIdOf);
            bySlotId.clear();
            bySlotId.putAll(newBySlotId);
            cachedTotal = total;
            nextSlotId = next;
            structureVersion++;
            contentVersion++;
            this.inventory = Collections.synchronizedList(loaded);
        }
    }

    /** Restores the slot ids a client needs to understand deltas. */
    public void readFromPacketNBT(NBTTagCompound tag) {
        readFromNBT(tag);
        synchronized (inventory) {
            int[] slots = tag.getIntArray("InternalSlots");
            int next = tag.getInteger("NextSlotId");
            if (next <= 0) {
                next = slotCount() + 1;
            }
            if (slots.length == slotCount()) {
                slotIdOf.clear();
                bySlotId.clear();
                for (int i = 0; i < slots.length; i++) {
                    ItemStack group = inventory.get(i);
                    ItemKey key = ItemKey.of(group);
                    if (key == null) {
                        continue;
                    }
                    int slotId = slots[i] > 0 ? slots[i] : next++;
                    slotIdOf.put(key, Integer.valueOf(slotId));
                    bySlotId.put(Integer.valueOf(slotId), group);
                }
            }
            nextSlotId = next;
        }
    }
}
