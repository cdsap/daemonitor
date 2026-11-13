// Captures a synthetic daemonitor-cli details view for README / site screenshots.
//
//	cd cored && go run ./cmd/docscreenshot | python3 ../scripts/ansi_to_png.py ../docs/images/cli-monitor.png
package main

import (
	"fmt"
	"os"

	"github.com/cdsap/daemonitor/cored/internal/tui"
)

func main() {
	fmt.Fprint(os.Stdout, tui.RenderCliDocsScreenshot())
}
