# Third-party software in Oba

Oba is released under the [MIT License](LICENSE). Its installers and jars include the
following software, each under its own license:

| Component | Use in Oba | License |
|---|---|---|
| [OpenJFX](https://openjfx.io) (JavaFX) | Desktop user interface | GPL v2 with the Classpath Exception |
| [SQLite JDBC](https://github.com/xerial/sqlite-jdbc) (includes SQLite, public domain) | Local message history, server database | Apache License 2.0 |
| [ZXing](https://github.com/zxing/zxing) | QR codes for safety numbers | Apache License 2.0 |
| [JNA](https://github.com/java-native-access/jna) | Windows DPAPI for "remember me" | Apache License 2.0 (or LGPL 2.1) |
| [Concentus](https://github.com/jaredmdobson/concentus) (a Java port of the [Opus](https://opus-codec.org) reference library; © Skype Limited, Xiph.Org Foundation, CSIRO, Microsoft Corporation, Jean-Marc Valin, Gregory Maxwell, Mark Borgerding, Timothy B. Terriberry, Logan Stromberg) | Opus voice codec for calls and voice rooms | BSD 3-Clause ([text](https://raw.githubusercontent.com/lostromb/concentus/master/LICENSE)) |
| [Feather icons](https://github.com/feathericons/feather) | Interface icons (adapted) | MIT |
| Java runtime (installers only; [Eclipse Temurin](https://adoptium.net) / OpenJDK) | Runs the app | GPL v2 with the Classpath Exception |

No third-party cryptography library is used: all encryption comes from the Java platform.
