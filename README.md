# PeaceCollection

## Run the website

From the project directory, enter a private owner password of at least 12 characters at the secure prompt, then start the Java server:

```powershell
$securePassword = Read-Host "Owner password (12+ characters)" -AsSecureString
$passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
try {
  $env:PEACECOLLECTION_ADMIN_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)
} finally {
  [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
}
javac -d out src\PeaceCollectionApplication.java
java -cp out PeaceCollectionApplication
```

Open `http://127.0.0.1:8080` for the storefront and `http://127.0.0.1:8080/admin` for the owner sign-in. The server binds to localhost by default. Use a private password and do not put it in source files or share it.

Optional environment settings:

- `PORT` is used by Render; `PEACECOLLECTION_PORT` changes the local listening port (default `8080`).
- `PEACECOLLECTION_HOST` changes the listening address (default `0.0.0.0`; use `127.0.0.1` to restrict local development to this computer).
- `PEACECOLLECTION_DATA_DIR` selects where catalogue, homepage text and styling requests are saved.
- `PEACECOLLECTION_PUBLIC_URL` sets the canonical HTTPS origin when using a custom domain. Render's assigned public URL is detected automatically.
- `PEACECOLLECTION_SECURE_COOKIES=true` adds the `Secure` cookie attribute when the site is served over HTTPS.

The owner panel can add, edit and remove products; change the announcement, welcome and story text; and review, update or delete customer styling requests. The shop starts with 62 catalogue looks, with multiple colours represented across the Dera styles, including Backless, Elegance, Boubou, Wrap, Maxi, Flare-sleeve, Occasion, kaftan, print and mixed-colour looks. Storefront and owner-panel visuals put real Dera fabric details cropped from local garment photos on a faceless, computer-rendered mannequin body. No real model is shown. The mannequin and garment shapes are mockups, not photographs of the exact finished products.

Customer requests and catalogue/content edits are saved in `peacecollection-data` beside the project. Back up that folder and restrict access to the computer/account running the server. To change the owner password, update `PEACECOLLECTION_ADMIN_PASSWORD` in the environment used to start the Java application, then restart the server. In Eclipse, configure it under the run configuration's **Environment** tab.

## Publish on Render and submit to Google

The repository includes a `Dockerfile` and `render.yaml` for a Render web service. The service is configured with a persistent 1 GB disk at `/var/data`; Render requires a paid web-service plan to attach a persistent disk. Check the current price and disk charge in Render before deploying. Do not deploy as a free service for the shop: its product and customer-request data would be lost on restarts.

1. Push this project to a GitHub repository that you control. A private repository is fine; the deployed website can still be public. Keep local customer/product data out of Git: the supplied `.gitignore` and `.dockerignore` exclude `peacecollection-data`, cookies, local settings and build files.
2. Sign in to Render, create a Blueprint from your GitHub repository, and review the service plan and persistent-disk charges before confirming deployment.
3. When prompted, set `PEACECOLLECTION_ADMIN_PASSWORD` to a private password of at least 12 characters. Never put it in Git or in `render.yaml`.
4. Wait for Render's deployment and health check to pass. Open the service's `onrender.com` URL and add `/admin` to sign in.
5. Open `https://search.google.com/search-console/`, add the public site as a URL-prefix property, complete Google's ownership verification, then submit `https://YOUR-SITE.onrender.com/sitemap.xml` under **Sitemaps**.

The site publishes `robots.txt`, a sitemap, a canonical URL and social-share metadata. Google decides when and whether a new site appears in results; deployment and sitemap submission do not guarantee immediate indexing or a particular ranking. A custom domain is optional and can be connected in the Render service settings.

This lightweight Java server is intended for local development or use behind a properly configured HTTPS reverse proxy. Do not expose its default HTTP listener directly to the public internet. When using an HTTPS proxy, set `PEACECOLLECTION_SECURE_COOKIES=true` and configure the proxy to protect the origin server.
