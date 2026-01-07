# CLAUDE.md

This file provides guidance for Claude Code when working on this project.

## Project Overview

This is an IntelliJ IDEA plugin written in Kotlin that provides a browser for S3 buckets running in LocalStack. The plugin allows users to browse, edit, and manage S3 objects directly from the IDE.

## Build Commands

```bash
# Build the plugin
./gradlew buildPlugin

# Run in sandbox IDE for testing
./gradlew runIde

# Run tests
./gradlew test

# Clean build artifacts
./gradlew clean
```

## Architecture

### Key Components

1. **S3ClientService** (`services/S3ClientService.kt`)
   - Singleton service managing AWS S3 client connections
   - Uses AWS SDK v2 with anonymous credentials for LocalStack
   - Designed for future multi-instance support via client caching

2. **S3TreeModel** (`toolwindow/S3TreeModel.kt`)
   - Lazy-loading tree model for the S3 browser
   - Caches children and loads asynchronously
   - Supports refresh at node level

3. **S3VirtualFileSystem** (`vfs/S3VirtualFileSystem.kt`)
   - Custom VFS implementation allowing S3 objects to be opened in editors
   - Handles explicit save (not auto-save) via `S3FileDocumentManagerListener`

4. **Settings** (`settings/`)
   - `S3BrowserAppSettings`: Application-level settings (default endpoint, timeouts, etc.)
   - `S3BrowserProjectSettings`: Project-level overrides

### Data Model

`S3TreeNode` is a sealed class hierarchy:
- `Root` - The LocalStack connection root
- `Bucket` - An S3 bucket
- `Folder` - A virtual folder (common prefix)
- `S3Object` - An S3 object (file)
- `Loading` - Placeholder while loading
- `Error` - Error display node

## Plugin Configuration

The plugin is configured in `src/main/resources/META-INF/plugin.xml`:
- Tool window registration
- Settings configurables
- Actions and action groups
- VFS registration
- Document save listener

## Testing Locally

1. Start LocalStack:
   ```bash
   localstack start
   ```

2. Create a test bucket:
   ```bash
   aws --endpoint-url=http://localhost:4566 s3 mb s3://test-bucket
   aws --endpoint-url=http://localhost:4566 s3 cp somefile.txt s3://test-bucket/
   ```

3. Run the plugin:
   ```bash
   ./gradlew runIde
   ```

## Common Tasks

### Adding a New Action
1. Create action class in `actions/` extending `AnAction`
2. Register in `plugin.xml` under appropriate action group
3. Use `S3BrowserPanel.S3_TREE_NODE` data key to get selected node

### Modifying Settings
1. Add field to `S3BrowserAppSettings.State` or `S3BrowserProjectSettings.State`
2. Add property accessor
3. Update corresponding `Configurable` class UI

### Extending S3 Operations
Add methods to `S3ClientService` - it handles client lifecycle and error wrapping.

## Dependencies

- IntelliJ Platform SDK 2024.1+
- AWS SDK for Java v2 (S3)
- Kotlin Coroutines (for async operations)
