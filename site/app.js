(() => {
  const header = document.querySelector(".site-header");
  if (header) {
    const syncHeader = () => {
      header.classList.toggle("is-scrolled", window.scrollY > 8);
    };
    syncHeader();
    window.addEventListener("scroll", syncHeader, { passive: true });
  }

  const platform = detectPlatform(navigator.userAgent || "", navigator.platform || "");
  if (platform) {
    document.querySelectorAll(".download-option").forEach((option) => {
      option.classList.toggle("is-preferred", option.dataset.os === platform);
    });
  }

  const releaseNote = document.getElementById("release-note");
  if (releaseNote) {
    fetchLatestReleaseTag()
      .then((tag) => {
        if (!tag) return;
        releaseNote.hidden = false;
        releaseNote.textContent = `Latest release on GitHub: ${tag}`;
      })
      .catch(() => {
        // Optional enhancement only — keep the page usable offline/without API access.
      });
  }

  function detectPlatform(userAgent, platformHint) {
    const haystack = `${userAgent} ${platformHint}`.toLowerCase();
    if (/windows|win32|win64/.test(haystack)) return "windows";
    if (/android/.test(haystack)) return null;
    if (/iphone|ipad|ipod/.test(haystack)) return "macos";
    if (/mac os|macintosh|macintel/.test(haystack)) return "macos";
    if (/linux|x11|cros/.test(haystack)) return "linux";
    return null;
  }

  async function fetchLatestReleaseTag() {
    const response = await fetch(
      "https://api.github.com/repos/cdsap/daemonitor/releases/latest",
      {
        headers: { Accept: "application/vnd.github+json" },
      },
    );
    if (!response.ok) return null;
    const payload = await response.json();
    return typeof payload.tag_name === "string" ? payload.tag_name : null;
  }
})();
