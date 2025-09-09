# OpenPaC BlueMap Integration - NeoForge Edition

This mod integrates [Open Parties and Claims](https://modrinth.com/mod/open-parties-and-claims) with [BlueMap](https://modrinth.com/mod/bluemap) to display land claims on your web maps in real-time.

## 🚀 Major Improvements from Original

This fork includes significant enhancements over the original Fabric version:

### ⚡ Real-Time Updates
- **Event-driven syncing** using OpenPaC's `IClaimsManagerListenerAPI`
- **Immediate claim updates** - no more waiting 10 minutes for changes to appear
- **Live chunk change detection** with `onChunkChange`, `onWholeRegionChange`, and `onDimensionChange` events

### 🔧 Platform Migration
- **NeoForge 1.21.1** support (migrated from Fabric)
- **Updated dependencies** for latest Minecraft version
- **Server-prefixed JAR naming** for easy identification

### 🛡️ Critical Bug Fixes
- **Ghost chunk elimination** - fixed issue where unclaimed chunks remained visible on BlueMap
- **Cache-first architecture** - prevents stale data from OpenPaC API
- **Memory leak prevention** - eliminated OutOfMemoryError crashes
- **Thread-safe operations** using `ConcurrentHashMap` and synchronized collections

### 🎯 Performance Optimizations
- **UUID-based marker identification** for consistent tracking
- **Incremental per-player updates** instead of full map rebuilds
- **Efficient chunk caching** system
- **Reduced update intervals** (5-minute fallback vs 10-minute)

### 🔍 Enhanced Debugging
- **Comprehensive logging** system
- **Cache consistency validation**
- **Event tracking** for troubleshooting

## 📋 Technical Implementation Details

### Event System Integration
```java
// Real-time event listeners
@Override
public void onChunkChange(ServerLevel serverLevel, ChunkPos chunkPos, UUID playerId, boolean claimed) {
    // Immediate cache and marker updates
}

@Override  
public void onWholeRegionChange(ServerLevel serverLevel, UUID playerId, boolean claimed) {
    // Handle mass claim changes efficiently
}
```

### Cache-First Data Consistency
```java
// Trust local cache over potentially stale API data
Set<ChunkPos> cachedChunks = chunkToPlayerCache.entrySet()
    .stream()
    .filter(entry -> entry.getValue().equals(playerId))
    .map(Map.Entry::getKey)
    .collect(Collectors.toSet());

if (cachedChunks.isEmpty()) {
    // Immediate marker removal - no API query needed
    markers.keySet().removeIf(k -> k.startsWith(playerId + "---"));
    return;
}
```

## 🔨 Installation

1. Install [NeoForge](https://neoforged.net/) for Minecraft 1.21.1
2. Install [Open Parties and Claims](https://modrinth.com/mod/open-parties-and-claims) (v0.23.0+)
3. Install [BlueMap](https://modrinth.com/mod/bluemap) (v5.0.0+)
4. Download and install this mod

## ⚙️ Configuration

The mod automatically configures with sensible defaults:
- **Update interval**: 5 minutes (fallback for missed events)
- **Real-time events**: Enabled by default
- **Cache validation**: Automatic consistency checks

## 🐛 Bug Reports

This version specifically addresses:
- ✅ **Ghost chunks** remaining after unclaiming
- ✅ **Memory crashes** during claim operations  
- ✅ **Delayed updates** taking up to 10 minutes
- ✅ **API data inconsistency** issues
- ✅ **Thread safety** concerns

## ⚠️ Known Issues & Stability

**Important:** This fork includes significant architectural changes that may introduce instability:

- **Event-driven architecture** is experimental and may cause unexpected behavior
- **Real-time updates** put additional load on the server during heavy claim operations
- **Memory usage** may be higher than the original due to caching systems
- **Edge cases** in claim/unclaim operations may not be fully tested

**Recommendations:**
- Test thoroughly in a development environment before production use
- Monitor server performance and memory usage
- Keep backups of your world and BlueMap data
- Report any issues with detailed logs and reproduction steps

Consider using the original Fabric version if stability is more important than real-time updates.

## 🔧 Development

### Building
```bash
./gradlew build
```
The built JAR will be prefixed with `server-` for easy identification.

### Dependencies
- Minecraft 1.21.1
- NeoForge 21.1.0+
- OpenPaC 0.23.0+
- BlueMap 5.0.0+

## 📚 Version History

### v1.2.1-neoforge (Current)
- Complete NeoForge migration
- Real-time event-driven updates
- Ghost chunk bug fixes
- Memory optimization
- Cache-first architecture

### Original Fabric Version
- Timer-based updates (10-minute intervals)
- Basic BlueMap integration
- Fabric platform support

## 🤝 Contributing

This is a modernized fork with significant architectural improvements. Feel free to submit issues or pull requests for further enhancements.

## 📄 License

MIT License - Same as original project