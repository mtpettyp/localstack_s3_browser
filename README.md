# LocalStack S3 Browser - IntelliJ Plugin

An IntelliJ IDEA plugin for browsing and managing S3 buckets running in LocalStack.

## Features

- **Browse S3 Content**: View all buckets and objects in a hierarchical tree view
- **Edit Files**: Open S3 files directly in the IntelliJ editor with explicit save back to S3
- **File Operations**: Create, delete, rename, and move files and folders
- **Bucket Management**: Create and delete S3 buckets
- **Drag & Drop**: Upload files by dragging from your filesystem into the tree
- **Copy S3 Paths**: Quick copy of `s3://bucket/key` paths to clipboard
- **Configurable**: Both application-level and project-level settings

## Requirements

- IntelliJ IDEA 2024.1 or later
- Java 17+
- LocalStack running with S3 service (default: `http://localhost:4566`)

## Building the Plugin

```bash
# Build the plugin distribution
./gradlew buildPlugin

# The plugin zip will be in build/distributions/
```

## Running in Development

```bash
# Launch a sandbox IntelliJ instance with the plugin installed
./gradlew runIde
```

## Installation

### From Disk
1. Build the plugin: `./gradlew buildPlugin`
2. In IntelliJ: **Settings → Plugins → ⚙️ → Install Plugin from Disk...**
3. Select `build/distributions/localstack-s3-browser-1.0.0.zip`
4. Restart IDE

## Usage

1. **Start LocalStack** with S3 enabled:
   ```bash
   localstack start -s s3
   ```

2. **Open the Tool Window**: View → Tool Windows → LocalStack S3 (or click the S3 icon in the right sidebar)

3. **Configure Endpoint** (if not using default):
   - Settings → Tools → LocalStack S3 Browser
   - Set your LocalStack endpoint URL

4. **Browse and Edit**:
   - Expand buckets to see folders and files
   - Double-click files to open in editor
   - Right-click for context menu (create, delete, rename, etc.)
   - Drag files from your filesystem to upload

## Configuration

### Application Settings (Settings → Tools → LocalStack S3 Browser)
- Default endpoint URL
- Default region
- Connection timeout
- Auto-refresh settings
- Confirmation dialogs toggle

### Project Settings (Settings → Tools → LocalStack S3 Browser → Project Settings)
- Override endpoint/region per project
- Default bucket to expand

## Project Structure

```
src/main/kotlin/com/github/localstack/s3browser/
├── actions/        # UI actions (create, delete, rename, upload, etc.)
├── model/          # Data models (S3TreeNode hierarchy)
├── services/       # S3 client service (AWS SDK v2)
├── settings/       # Application and project settings
├── toolwindow/     # Tool window UI (tree, panel, renderer)
└── vfs/            # Virtual File System for S3 files
```

## License

MIT
