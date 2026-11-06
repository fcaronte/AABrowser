# AABrowser

Contesto e regole del progetto, caricati automaticamente a ogni sessione di Claude Code
(terminale o plugin di Android Studio):

@AABrowser.md
@PROJECT_MEMORY.md

## Verifica compilazione

```
./gradlew --offline :browser:compileDebugKotlin
```

Test: `./gradlew --offline :browser:testDebugUnitTest` (Kotlin) e `node --test browser/src/test/js/` (titoli dei brani, `assets/player/track-parser.js`).
