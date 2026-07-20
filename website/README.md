# Shoaku landing page

Static, dependency-free landing page for Shoaku.

Product images are referenced as regular files from `website/images/`. Add an
image there and use a relative path such as `images/shoaku-overview.webp` in an
`<img>` element. This keeps images deployable with the static site and avoids
embedding large image data in the HTML.

```sh
cd website
python3 -m http.server 8080
```

Then open <http://localhost:8080>.
