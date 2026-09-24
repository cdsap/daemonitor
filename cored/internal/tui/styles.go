package tui

import (
	lipgloss "charm.land/lipgloss/v2"
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
