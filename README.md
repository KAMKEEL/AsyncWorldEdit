AsyncWorldEdit
==============
The plugin was split into free and premium version. 
Want more features? Want more frequent updates? Go to: [AsyncWorldEdit - Premium](https://github.com/SBPrime/AsyncWorldEdit-Premium)

Please read the project license.

Async WorldEdit - Edit millions of blocks without lag!

This plugin has only one function: eliminate the lag caused by the WorldEdit! 
This is done by replacing the WorldEdits session with an special asynchronous 
one. All the block drawing is done in packages. The package size and how often
the blocks are drawn are configurable in the config. AsyncWorldEdit is not a
reimplementation of WorldEdit! It attaches the original WorldEdit API and 
WorldEdit classes and tries to fix the lags. There fore you have access to all
the WorldEdit operations, and all those operations work exactly like in the 
original WorldEdit. So basically you get the same commands, same permissions 
and the same experience. In addition to that if WorldEdit team releases a new
feature, fixes a bug all you need to do is update WorldEdit and AWE will do its
job as usual.

## Building from source

AsyncWorldEdit is organised as a multi‑module [Maven](https://maven.apache.org/) project. All source modules are included in this repository.

1. **Clone the repository**

   ```bash
   git clone https://github.com/SBPrime/AsyncWorldEdit.git
   cd AsyncWorldEdit
   ```

2. **Compile and package**

   ```bash
   mvn clean package
   ```

   Maven will download required dependencies and build every module. The plugin jar is created at `AsyncWorldEdit-Deploy/target/AsyncWorldEdit.jar`.

   If you prefer a one‑step setup that downloads dependencies and builds the jar, run:

   ```bash
   ./scripts/setup-dev.sh
   ```

## Patching only `ClassScanner`

You can modify only the `ClassScanner` class without rebuilding the whole project.

1. Edit `AsyncWorldEdit/src/main/java/org/primesoft/asyncworldedit/injector/scanner/ClassScanner.java`.
2. Recompile that single file against the existing jar and bundled libraries:

   ```bash
   cd AsyncWorldEdit
   javac -cp ../AsyncWorldEdit-Deploy/target/AsyncWorldEdit.jar:../libs/* \
         -d target/classes \
         src/main/java/org/primesoft/asyncworldedit/injector/scanner/ClassScanner.java
   ```

3. Inject the compiled class into the plugin jar:

   ```bash
   jar uf ../AsyncWorldEdit-Deploy/target/AsyncWorldEdit.jar \
       -C target/classes org/primesoft/asyncworldedit/injector/scanner/ClassScanner.class
   ```

   The above two steps can be performed automatically with:

   ```bash
   ./scripts/patch-classscanner.sh
   ```

## Working with IntelliJ IDEA

1. **Get the sources**
   - Clone the repository or, from the IntelliJ welcome screen, choose *Get from VCS* and enter the project URL.
   - Optionally run `./scripts/setup-dev.sh` in a terminal to download dependencies and build the jar before opening the project.

2. **Open the project**
   - From the welcome screen choose *Open* and select the root `pom.xml`.
   - IntelliJ detects the Maven structure, imports all modules and indexes dependencies.

3. **Build the plugin**
   - Use the *Maven* tool window and run the `package` goal for the root project (Lifecycle → `package`).
   - The compiled jar is written to `AsyncWorldEdit-Deploy/target/AsyncWorldEdit.jar`.

4. **Edit and recompile `ClassScanner`**
   - Navigate to `AsyncWorldEdit/src/main/java/.../ClassScanner.java`, make your changes and choose *Recompile 'ClassScanner.java'* (Build → Recompile).

5. **Patch the plugin jar**
   - Execute `./scripts/patch-classscanner.sh` to insert the newly compiled class into `AsyncWorldEdit-Deploy/target/AsyncWorldEdit.jar`.

6. **Test**
   - Copy the jar from `AsyncWorldEdit-Deploy/target/` to your server's `plugins` directory and restart the server.
