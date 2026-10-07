package tui

import (
	"strings"

	lipgloss "charm.land/lipgloss/v2"

	"github.com/cdsap/daemonitor/cored/internal/model"
	"github.com/cdsap/daemonitor/cored/internal/render"
)

const (
	colorGreen   = "10"
	colorYellow  = "11"
	colorRed     = "9"
	colorCyan    = "14"
	colorBlue    = "12"
	colorMagenta = "13"
)

func titleStyle(noColor bool) lipgloss.Style {
	s := lipgloss.NewStyle().Bold(true)
	if noColor {
		return s
	}
	return s.Foreground(lipgloss.Color("14"))
}

func errorStyle(noColor bool) lipgloss.Style {
	s := lipgloss.NewStyle()
	if noColor {
		return s
	}
	return s.Foreground(lipgloss.Color("9"))
}

func selectedStyle(noColor bool) lipgloss.Style {
	s := lipgloss.NewStyle()
	if noColor {
		return s.Bold(true)
	}
	return s.Bold(true).Foreground(lipgloss.Color("15")).Background(lipgloss.Color("4"))
}

func headerStyle(noColor bool) lipgloss.Style {
	s := lipgloss.NewStyle().Bold(true)
	if noColor {
		return s
	}
	return s.Foreground(lipgloss.Color(colorCyan))
}

func detailHeadingStyle(noColor bool) lipgloss.Style {
	s := lipgloss.NewStyle().Bold(true)
	if noColor {
		return s
	}
	return s.Foreground(lipgloss.Color(colorCyan))
}

func activePaneStyle(noColor bool) lipgloss.Style {
	s := lipgloss.NewStyle().Bold(true)
	if noColor {
		return lipgloss.NewStyle()
	}
	return s.Foreground(lipgloss.Color("15")).Background(lipgloss.Color("4"))
}

func activePaneBorderStyle(noColor bool) lipgloss.Style {
	s := lipgloss.NewStyle().Bold(true)
	if noColor {
		return lipgloss.NewStyle()
	}
	return s.Foreground(lipgloss.Color(colorCyan))
}

func statusStyle(status string, noColor bool) lipgloss.Style {
	s := lipgloss.NewStyle().Bold(true)
	if noColor {
		return s
	}
	lower := strings.ToLower(status)
	switch {
	case strings.Contains(lower, "disconnect") || strings.Contains(lower, "failed"):
		return s.Foreground(lipgloss.Color(colorRed))
	case status == "PAUSED" || strings.Contains(lower, "connecting"):
		return s.Foreground(lipgloss.Color(colorYellow))
	default:
		return s.Foreground(lipgloss.Color(colorGreen))
	}
}

func rssStyle(p model.Process, noColor bool) lipgloss.Style {
	s := lipgloss.NewStyle()
	if noColor {
		return s
	}
	switch {
	case p.RSSMemoryMB >= render.MemoryCritMB:
		return s.Foreground(lipgloss.Color(colorRed)).Bold(true)
	case p.RSSMemoryMB >= render.MemoryWarnMB:
		return s.Foreground(lipgloss.Color(colorYellow)).Bold(true)
	default:
		return s.Foreground(lipgloss.Color(colorBlue))
	}
}

func cpuStyle(p model.Process, noColor bool) lipgloss.Style {
	s := lipgloss.NewStyle()
	if noColor || p.CPUPercent == nil {
		return s
	}
	switch {
	case *p.CPUPercent >= 95:
		return s.Foreground(lipgloss.Color(colorRed)).Bold(true)
	case *p.CPUPercent >= 80:
		return s.Foreground(lipgloss.Color(colorYellow))
	default:
		return s.Foreground(lipgloss.Color(colorGreen))
	}
}

func heapStyle(noColor bool) lipgloss.Style {
	s := lipgloss.NewStyle()
	if noColor {
		return s
	}
	return s.Foreground(lipgloss.Color(colorMagenta))
}

func signalStyle(signal string, noColor bool) lipgloss.Style {
	s := lipgloss.NewStyle().Bold(true)
	if noColor {
		return s
	}
	if strings.Contains(signal, "CRIT") {
		return s.Foreground(lipgloss.Color(colorRed))
	}
	if strings.Contains(signal, "HIGH") {
		return s.Foreground(lipgloss.Color(colorYellow))
	}
	return s.Foreground(lipgloss.Color(colorCyan))
}
