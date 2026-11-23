package io.github.mebahel.zombiehorde.horde;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * REFERENCE: set of UUIDs of currently loaded patrolling mobs. Fill it from EntityAddToWorldEvent (only when PDC
 * patrolling=true), empty it from EntityRemoveFromWorldEvent. Use it for reload re-attachment and any sweep
 * instead of World#getEntities() (which walks every entity on the server). Main thread only.
 */
public final class HordeRegistry {
    private final Set<UUID> members = new LinkedHashSet<>();

    public boolean add(UUID id) { return members.add(id); }
    public boolean remove(UUID id) { return members.remove(id); }
    public int size() { return members.size(); }
    public boolean contains(UUID id) { return members.contains(id); }
    /** Copy so callers may add/remove while iterating. */
    public List<UUID> snapshot() { return new ArrayList<>(members); }
    public void clear() { members.clear(); }
}
