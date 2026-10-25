# Machine Chunk Loader

A NeoForge 26.2 mod that keeps **NTM NEXT**, **NTM NEXT: Space** and **BuildCraft UNOFFICIAL** machines, cables and pipes running when no player is nearby. Quarries, pumps, oil derricks, excavators and mining lasers keep working over their whole area. It runs on the server only, so players can join without installing it.

## How it works

Minecraft can only load whole chunks, so the mod finds every chunk that holds a machine or a network block and keeps it loaded. In the default **machine-only mode** those chunks are held in memory but not simulated. The mod then ticks only the machines itself, which costs far less than a normal chunk loader.

- **Machines** are found automatically when a player places one, when their chunk loads, and by a scan around each player every 5 seconds. A machine is any block entity from the listed mods that ticks on the server, minus a list of world-generated blocks such as geysers and spawners.
- **Network blocks** (NTM cables, fluid pipes, pylons) keep their chunk loaded so power and fluid networks stay connected. BuildCraft pipes are block entities and count as machines.
- **Area machines** also load the chunks they work on: about 9x9 chunks for NTM derricks, pumpjacks, fracking towers and BuildCraft pumps. Excavators get 3x3, mining lasers 5x5, and BuildCraft Fillers and Builders get every chunk in their box. BuildCraft already loads the Quarry's frame area itself.
- **Multiblocks and border pipes**: a machine within 6 blocks of a chunk border also loads the chunk across it.
- **When a player comes close**, vanilla takes over ticking that chunk and the mod steps aside, so nothing runs twice.
- **Restarts**: tracked machines are saved with each dimension and come back on server start.

## What stays frozen in machine-only chunks

Mobs, dropped items, crops, redstone, flowing water and lava, and random ticks. NTM conveyors move items as entities, so chunks with conveyors are fully simulated by default. If another machine misbehaves, add its block entity id to `fullTicking` in the config, or set `machineOnlyMode = false` to fully load every machine chunk.

## Config

`config/machinechunkloader-common.toml`, reloaded live when edited.

| Setting | Default | What it does |
| --- | --- | --- |
| `general.enabled` | `true` | Master switch |
| `general.machineOnlyMode` | `true` | `false` fully simulates every machine chunk |
| `general.modNamespaces` | `hbm`, `hbmspace`, `buildcraftunofficial` | Which mods' machines count |
| `machines.excluded` | world-gen blocks | Block entity ids never loaded |
| `machines.fullTicking` | empty | Block entity ids whose chunks are fully simulated |
| `machines.areaRadius` | derricks 4, pumps 4, excavator 1, mining laser 2 | Extra chunks around area machines |
| `machines.boxArea` | Filler, Builder | Machines whose marked box is loaded |
| `machines.edgeMarginBlocks` | `6` | Border distance that also loads the neighbour chunk |
| `networks.networkBlocks` | NTM cables, pipes, pylons | Blocks that keep their chunk loaded |
| `networks.fullTickingBlocks` | NTM conveyors | Blocks whose chunk is fully simulated |

## Commands (operators)

- `/machineloader stats` shows tracked machines and held chunks per dimension.
- `/machineloader list [page]` lists machines in your dimension with their position and mode.
- `/machineloader rescan [radius]` scans chunks around you for machines right away.

## Building

Needs Java 25 and network access to `maven.neoforged.net` and Mojang's servers.

```
./gradlew build
```

The jar lands in `build/libs/`.
