# Third-party notices

## HayulGUI

The setup tool includes the patch engine and stub from [HayulGUI](https://github.com/dolidolih/HayulGUI),
revision `bb4e934dbb6002a01bcf28ed244b5292378ce228`, with local changes: the Gradle build files
(`starlight.gradle.kts`), stream reads that work below Android API 33, installer messages, and manifest attributes inserted in
resource id order (marked `[kakaodb]`). The upstream MIT license and full attribution are in
`vendor/HayulGUI/LICENSE` and `vendor/HayulGUI/NOTICE`. The MIT permission notice below applies to these
portions as well. The bundled `com.android.tools.build:apksig` is licensed under Apache License 2.0;
see `vendor/HayulGUI/NOTICE`.

## Iris

`rootd/src/main/java/dev/mooner/kakaodb/rootd/CryptoUserDatabase.java` ports code from
[Iris](https://github.com/dolidolih/Iris) (`UserDbSaltStore.kt`, `CryptoUserDatabaseReader.kt`, `SqlCipherNative.kt`):
reading the `crypto_user_database` passphrase seed, deriving the passphrase, and loading `libsqlcipher.so` from the
running APK.

```
MIT License

Copyright (c) 2026 dolidolih

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## SQLCipher for Android

The root helper APK bundles [SQLCipher for Android](https://github.com/sqlcipher/sqlcipher-android)
(`net.zetetic:sqlcipher-android`), under its own license (BSD-style); see the project for its terms.
