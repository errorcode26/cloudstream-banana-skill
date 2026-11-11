# Contributing Guidelines

Thank you for your interest in improving the CloudStream 3 Plugin Builder Skill. Before opening an issue or pull request, carefully review the following rules and requirements.

---

## Strict Rules on Pull Requests

### 1. Absolute Prohibition of Hardcoded Extractor Logic & Site-Specific Warez
* **NEVER** submit pull requests containing hardcoded URLs to copyrighted video files, pirated streaming servers, or specific piracy domains.
* **NEVER** commit proprietary DRM bypass keys, private decryption secrets, or hardcoded session cookies.
* PRs containing site-specific hacks or hardcoded extractors targeting specific third-party movie/TV hosts will be **summarily rejected and closed without review**.
* All contributions must focus strictly on **generic, reusable architectural patterns**:
  * Abstract parser utilities and DOM helpers.
  * Integration with upstream `recloudstream/cloudstream` SDK models.
  * Universal unpacker algorithms (e.g. Dean Edwards packer unpackers, standard crypto helpers).
  * Build tooling, Gradle optimization, and test harnesses.

### 2. Mandatory Self-Testing Before Submitting
* Contributors **must test their changes locally** before opening a pull request.
* Run the headless JVM unit test suite:
  ```bash
  ./gradlew testDebugUnitTest
  # or on Windows:
  .\gradlew.bat testDebugUnitTest
  ```
* Verify that:
  * All unit tests compile and pass cleanly without warnings.
  * Existing templates compile (`./gradlew make` or `.\gradlew.bat make`).
  * No external breaking changes are introduced to the core lifecycle (`MainAPI`, `Plugin`, `ExtractorApi`).

### 3. Dependency & Code Style Standards
* **Pin Jackson**: Never bump Jackson dependencies above `2.13.1` in `build.gradle.kts`. Newer versions introduce bytecode incompatibilities on Android runtimes.
* **No Unsolicited Settings**: Do not wire up `SettingsFragment` or `openSettings` in base templates. Extensions must remain lean by default.
* **Clean Code**: Follow official Kotlin coding conventions. Keep docstrings and comments informative and professional.
* **No Leaked Identity**: Ensure no author tokens, machine-specific paths, or private repositories are left in manifests or source files.

---

## How to Submit a Pull Request

1. Fork the repository and create your feature branch from `main`:
   ```bash
   git checkout -b feature/generic-parser-improvement
   ```
2. Commit your changes with clear, descriptive commit messages.
3. Test locally and ensure all tests pass.
4. Push to your fork and submit a Pull Request against `main`.
5. Provide a concise explanation of what the PR changes, why it is needed, and evidence of local test verification.
