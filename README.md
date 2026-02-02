# JustTeams

<p align="center">
<img src="https://i.imgur.com/vsZRsRM.png" alt="JustTeams Logo" width="1000"/>
</p>

<p align="center">
<strong>A powerful teams plugin for multi-server environments with full Folia, Paper, Spigot, and Purpur support.</strong>
</p>

<p align="center">
<img src="https://img.shields.io/badge/Version-2.4.0-brightgreen?style=for-the-badge" alt="Version" />
<img src="https://img.shields.io/badge/API-1.21+-blue?style=for-the-badge" alt="API Version" />
<img src="https://img.shields.io/badge/Java-21+-orange?style=for-the-badge" alt="Java" />
<img src="https://img.shields.io/badge/Folia-Supported-purple?style=for-the-badge" alt="Folia" />
</p>

<p align="center">
<a href="https://discord.gg/HRjcmEYXNy">
<img src="https://img.shields.io/discord/1389677354753720352?color=5865F2&label=Discord&logo=discord&logoColor=white&style=for-the-badge" alt="Discord" />
</a>
</p>

---

## Overview

JustTeams is a feature-rich, high-performance teams plugin designed for modern Minecraft networks. Built with scalability in mind, it supports single servers, BungeeCord/Velocity proxies, and Folia's multi-threaded architecture.

### Key Features

- **High Performance** - Asynchronous operations and optimized database queries
- **Cross-Server Support** - Real-time synchronization via Redis + MySQL backend
- **Dual-Mode Sync** - Redis for instant updates (<100ms), MySQL polling fallback
- **Comprehensive Admin Tools** - Full-featured admin GUI with permission editing
- **Team Management** - Create, invite, promote, demote, kick, and transfer teams
- **Team Features** - Home system, warps, PvP toggle, team chat, shared ender chest
- **Admin Chat Spy** - Monitor all team chats with permission-based access
- **Economy Integration** - Vault support with team bank system
- **Rich Visuals** - MiniMessage formatting with gradients and custom colors
- **Customizable GUIs** - Dummy items support for enhanced visual customization
- **Permission System** - Role-based permissions (Owner, Admin, Moderator, Member)
- **Hook Support** - Vault, PlaceholderAPI, PvPManager (all optional)
- **Folia Ready** - Full support for multi-threaded region servers with optimized scheduler
- **Geyser/Bedrock Support** - Native platform indicators and fully compatible menus
- **Translatable Help** - Fully customizable help system via `messages.yml`

---

## Installation

### Quick Start

1. Download `JustTeams.jar` from releases.
2. Place in your server's `plugins` folder.
3. Restart the server to generate configuration files.
4. Configure `config.yml` (and `messages.yml`/`gui.yml` as needed).
5. Reload with `/team reload` or restart.

### Dependencies

**Required:**
- Java 21 or higher
- Paper 1.21+ (or Folia, Spigot 1.21+ compatible)

**Optional:**
- **Vault** - For team economy features.
- **PlaceholderAPI** - For using team placeholders in chat/scoreboards.
- **PacketEvents** - For glowing player effects.
- **MySQL/MariaDB** - For data storage (recommended for production).
- **Redis** - For cross-server synchronization.

---

## Documentation

For full documentation, including advanced configuration, permissions, and API usage, please visit our [Wiki](https://kotori.ink/wiki/justteams).

---

## License & Credits

**JustTeams** is developed and maintained with care by **kotori**.

This plugin is open-source software, licensed under the CC BY-NC-SA 4.0 License.
