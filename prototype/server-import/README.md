# Riffle server-import prototype

Standalone localhost prototype for uploading a Web Source item into a selected server library.

## Run

From the repository root:

```sh
python3 -m http.server 4173 --directory prototype/server-import
```

Open <http://localhost:4173>.

The prototype has controls for:

- the subtle top-right overflow menu on item detail;
- Web Source vs. Server Source item visibility;
- configured servers vs. no configured servers;
- EPUB vs. multi-track audiobook representation;
- no matching item → upload;
- compatible existing item → safe file overwrite;
- existing annotations + incompatible replacement → overwrite blocked.
