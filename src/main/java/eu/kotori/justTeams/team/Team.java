package eu.kotori.justTeams.team;

import eu.kotori.justTeams.JustTeams;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public class Team implements InventoryHolder {
    private final int id;
    private volatile String name;
    private volatile String tag;
    private volatile String description;
    private volatile UUID ownerUuid;
    private volatile Location homeLocation;
    private volatile String homeServer;
    private volatile java.sql.Timestamp creationDate;
    private final AtomicBoolean pvpEnabled = new AtomicBoolean(false);
    private final AtomicBoolean isPublic = new AtomicBoolean(false);
    private final AtomicBoolean glowEnabled = new AtomicBoolean(true);
    private final AtomicReference<Double> balance = new AtomicReference<>(0.0);
    private final AtomicInteger kills = new AtomicInteger(0);
    private final AtomicInteger deaths = new AtomicInteger(0);
    private final List<TeamPlayer> members;
    private Inventory enderChest;
    private final List<UUID> joinRequests;
    private final AtomicBoolean enderChestLock = new AtomicBoolean(false);
    private final List<UUID> enderChestViewers = new CopyOnWriteArrayList<>();
    private volatile SortType currentSortType = SortType.JOIN_DATE;

    public Team(int id, String name, String tag, UUID ownerUuid,
            boolean defaultPvpStatus, boolean defaultPublicStatus, boolean defaultGlowStatus) {
        this(id, name, tag, ownerUuid, defaultPvpStatus, defaultPublicStatus, defaultGlowStatus,
                new java.sql.Timestamp(System.currentTimeMillis()));
    }

    public Team(int id, String name, String tag, UUID ownerUuid,
            boolean defaultPvpStatus, boolean defaultPublicStatus, boolean defaultGlowStatus,
            java.sql.Timestamp creationDate) {
        this.id = id;
        this.name = name;
        this.tag = tag;
        this.ownerUuid = ownerUuid;
        this.pvpEnabled.set(defaultPvpStatus);
        this.isPublic.set(defaultPublicStatus);
        this.glowEnabled.set(defaultGlowStatus);
        this.creationDate = creationDate;
        this.members = new CopyOnWriteArrayList<>();
        this.joinRequests = new CopyOnWriteArrayList<>();
    }

    public int getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getTag() {
        return tag != null ? tag : "";
    }

    public String getDescription() {
        return description != null ? description : "A new Team!";
    }

    public UUID getOwnerUuid() {
        return ownerUuid;
    }

    public Location getHomeLocation() {
        return homeLocation;
    }

    public String getHomeServer() {
        return homeServer;
    }

    public java.sql.Timestamp getCreationDate() {
        return creationDate;
    }

    public void setCreationDate(java.sql.Timestamp creationDate) {
        this.creationDate = creationDate;
    }

    public boolean isPvpEnabled() {
        return pvpEnabled.get();
    }

    public boolean isPublic() {
        return isPublic.get();
    }

    public double getBalance() {
        return balance.get();
    }

    public void setBalance(double balance) {
        this.balance.set(balance);
    }

    public void addBalance(double amount) {
        this.balance.updateAndGet(current -> current + amount);
    }

    public void removeBalance(double amount) {
        this.balance.updateAndGet(current -> current - amount);
    }

    public int getKills() {
        return kills.get();
    }

    public void setKills(int kills) {
        this.kills.set(kills);
    }

    public void incrementKills() {
        this.kills.incrementAndGet();
    }

    public int getDeaths() {
        return deaths.get();
    }

    public void setDeaths(int deaths) {
        this.deaths.set(deaths);
    }

    public void incrementDeaths() {
        this.deaths.incrementAndGet();
    }

    public List<TeamPlayer> getMembers() {
        return members;
    }

    public Inventory getEnderChest() {
        return enderChest;
    }

    public void setEnderChest(Inventory enderChest) {
        this.enderChest = enderChest;
    }

    public List<UUID> getJoinRequests() {
        return joinRequests;
    }

    public boolean isEnderChestLocked() {
        return enderChestLock.get();
    }

    public boolean tryLockEnderChest() {
        return enderChestLock.compareAndSet(false, true);
    }

    public void unlockEnderChest() {
        enderChestLock.set(false);
    }

    public List<UUID> getEnderChestViewers() {
        return enderChestViewers;
    }

    public void addEnderChestViewer(UUID playerUuid) {
        if (!enderChestViewers.contains(playerUuid)) {
            enderChestViewers.add(playerUuid);
        }
    }

    public void removeEnderChestViewer(UUID playerUuid) {
        enderChestViewers.remove(playerUuid);
    }

    public boolean hasEnderChestViewers() {
        return !enderChestViewers.isEmpty();
    }

    public SortType getCurrentSortType() {
        return currentSortType;
    }

    public void setSortType(SortType sortType) {
        this.currentSortType = sortType;
    }

    public void cycleSortType() {
        SortType currentSort = getCurrentSortType();
        SortType newSort = switch (currentSort) {
            case JOIN_DATE -> SortType.ALPHABETICAL;
            case ALPHABETICAL -> SortType.ONLINE_STATUS;
            case ONLINE_STATUS -> SortType.JOIN_DATE;
        };
        setSortType(newSort);
    }

    public void addJoinRequest(UUID playerUuid) {
        if (!joinRequests.contains(playerUuid)) {
            joinRequests.add(playerUuid);
        }
    }

    public void removeJoinRequest(UUID playerUuid) {
        joinRequests.remove(playerUuid);
    }

    public List<TeamPlayer> getCoOwners() {
        return members.stream().filter(m -> m.getRole() == TeamRole.CO_OWNER).collect(Collectors.toList());
    }

    public List<TeamPlayer> getSortedMembers(SortType sortType) {
        return members.stream().sorted(sortType.getComparator()).collect(Collectors.toList());
    }

    public void addMember(TeamPlayer player) {
        this.members.add(player);
    }

    public void removeMember(UUID playerUuid) {
        this.members.removeIf(member -> member.getPlayerUuid().equals(playerUuid));
    }

    public boolean isMember(UUID playerUuid) {
        return members.stream().anyMatch(member -> member.getPlayerUuid().equals(playerUuid));
    }

    public boolean isOwner(UUID playerUuid) {
        return this.ownerUuid.equals(playerUuid);
    }

    public boolean hasElevatedPermissions(UUID playerUuid) {
        TeamPlayer member = getMember(playerUuid);
        if (member == null)
            return false;
        return member.getRole() == TeamRole.OWNER || member.getRole() == TeamRole.CO_OWNER;
    }

    public TeamPlayer getMember(UUID playerUuid) {
        return members.stream().filter(m -> m.getPlayerUuid().equals(playerUuid)).findFirst().orElse(null);
    }

    public void broadcast(String messageKey, TagResolver... resolvers) {
        members.forEach(member -> {
            if (member.isOnline()) {
                JustTeams.getInstance().getMessageManager().sendMessage(member.getBukkitPlayer(), messageKey,
                        resolvers);
            }
        });
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setTag(String tag) {
        this.tag = tag;
    }

    public String getColoredName() {
        return name;
    }

    public String getColoredTag() {
        return tag != null ? tag : "";
    }

    public String getPlainName() {
        return stripColorCodes(name);
    }

    public String getPlainTag() {
        return stripColorCodes(tag != null ? tag : "");
    }

    private String stripColorCodes(String text) {
        if (text == null)
            return "";
        return text.replaceAll("(?i)&[0-9A-FK-OR]", "").replaceAll("(?i)<#[0-9A-F]{6}>", "")
                .replaceAll("(?i)</#[0-9A-F]{6}>", "");
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setOwnerUuid(UUID ownerUuid) {
        this.ownerUuid = ownerUuid;
    }

    public void setHomeLocation(Location homeLocation) {
        this.homeLocation = homeLocation;
    }

    public void setHomeServer(String homeServer) {
        this.homeServer = homeServer;
    }

    public void setPvpEnabled(boolean pvpEnabled) {
        this.pvpEnabled.set(pvpEnabled);
    }

    public void setPublic(boolean isPublic) {
        this.isPublic.set(isPublic);
    }

    public boolean isGlowEnabled() {
        return glowEnabled.get();
    }

    public void setGlowEnabled(boolean glowEnabled) {
        this.glowEnabled.set(glowEnabled);
    }

    private volatile org.bukkit.ChatColor color;

    public void setColor(org.bukkit.ChatColor color) {
        this.color = color;
    }

    public org.bukkit.ChatColor getColor() {
        return color;
    }

    public Inventory getInventory() {
        return this.enderChest;
    }

    public enum SortType {
        JOIN_DATE(Comparator.comparing(TeamPlayer::getJoinDate)),
        ALPHABETICAL(Comparator.comparing(p -> {
            String name = Bukkit.getOfflinePlayer(p.getPlayerUuid()).getName();
            return name != null ? name.toLowerCase() : "";
        })),
        ONLINE_STATUS(Comparator.comparing(TeamPlayer::isOnline).reversed());

        private final Comparator<TeamPlayer> comparator;

        SortType(Comparator<TeamPlayer> comparator) {
            this.comparator = comparator;
        }

        public Comparator<TeamPlayer> getComparator() {
            return this.comparator;
        }
    }

    // ==================== Custom Data Storage API ====================

    /**
     * Store custom data for this team.
     * 
     * @param key   The unique key for the data
     * @param value The serialized value to store
     * @return true if successfully stored, false otherwise
     */
    public boolean setCustomData(String key, String value) {
        return JustTeams.getInstance().getStorageManager().getStorage().setTeamCustomData(this.id, key, value);
    }

    /**
     * Retrieve custom data for this team.
     * 
     * @param key The unique key for the data
     * @return Optional containing the value if found, empty otherwise
     */
    public java.util.Optional<String> getCustomData(String key) {
        return JustTeams.getInstance().getStorageManager().getStorage().getTeamCustomData(this.id, key);
    }

    /**
     * Remove custom data for this team.
     * 
     * @param key The unique key for the data to remove
     * @return true if the data was removed, false if it didn't exist
     */
    public boolean removeCustomData(String key) {
        return JustTeams.getInstance().getStorageManager().getStorage().removeTeamCustomData(this.id, key);
    }

    /**
     * Get all custom data stored for this team.
     * 
     * @return A map of all key-value pairs stored for this team
     */
    public java.util.Map<String, String> getAllCustomData() {
        return JustTeams.getInstance().getStorageManager().getStorage().getAllTeamCustomData(this.id);
    }

    /**
     * Check if this team has custom data stored for a specific key.
     * 
     * @param key The unique key to check
     * @return true if data exists for this key, false otherwise
     */
    public boolean hasCustomData(String key) {
        return JustTeams.getInstance().getStorageManager().getStorage().hasTeamCustomData(this.id, key);
    }

    /**
     * Clear all custom data stored for this team.
     * 
     * @return The number of entries that were removed
     */
    public int clearAllCustomData() {
        return JustTeams.getInstance().getStorageManager().getStorage().clearAllTeamCustomData(this.id);
    }

    /**
     * Store custom object data for this team using a registered codec.
     * 
     * @param <T>   The type of the value
     * @param key   The unique key for the data
     * @param value The object to store
     * @return true if successfully stored, false otherwise (e.g. no codec found)
     */
    public <T> boolean setCustomData(String key, T value) {
        try {
            String serialized = JustTeams.getInstance().getCustomDataManager().serialize(value);
            return setCustomData(key, serialized);
        } catch (IllegalArgumentException e) {
            JustTeams.getInstance().getLogger()
                    .warning("Could not set custom data for key " + key + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Retrieve custom object data for this team using a registered codec.
     * 
     * @param <T>  The type of the object
     * @param key  The unique key for the data
     * @param type The class type to deserialize into
     * @return Optional containing the object if found and deserialized, empty
     *         otherwise
     */
    public <T> java.util.Optional<T> getCustomData(String key, Class<T> type) {
        java.util.Optional<String> data = getCustomData(key);
        if (data.isEmpty())
            return java.util.Optional.empty();

        eu.kotori.justTeams.api.ClanCustomDataCodec<T> codec = JustTeams.getInstance().getCustomDataManager()
                .getCodec(type);
        if (codec == null) {
            JustTeams.getInstance().getLogger().warning(
                    "No codec found for type " + type.getName() + " when retrieving custom data key " + key);
            return java.util.Optional.empty();
        }

        try {
            return java.util.Optional.ofNullable(codec.deserialize(data.get()));
        } catch (Exception e) {
            JustTeams.getInstance().getLogger()
                    .warning("Error deserializing custom data for key " + key + ": " + e.getMessage());
            return java.util.Optional.empty();
        }
    }
}
