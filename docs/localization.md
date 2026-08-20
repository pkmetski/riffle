# Localization

Riffle uses Android's standard resource directories for app strings:

- Default strings live in `app/src/main/res/values/strings.xml`.
- Localized strings live in `app/src/main/res/values-<locale>/strings.xml`.
- Strings with `translatable="false"` in the default file are intentionally shared and should not be copied into locale files.

## Add a Locale

Create or update a locale resource file:

```sh
make translation LOCALE=fr
```

Use Android resource locale tags, such as `bg`, `es`, or `pt-rBR`. The task creates blank entries for any required keys that are missing from the locale file.

Fill every generated value, then verify the translation set:

```sh
make check-translations
```

`checkTranslations` is also part of `riffleChecks` and normal Gradle `check`, so new app strings cannot be added without updating every existing locale.
