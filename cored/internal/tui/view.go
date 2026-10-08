package tui

import tea "charm.land/bubbletea/v2"

// View renders the monitor.
func (m Model) View() tea.View {
	var body string
	if m.width < 40 || m.height < 8 {
		body = "Terminal too small.\nPlease enlarge the window."
	} else if m.detailsOpen {
		body = m.renderDetails()
	} else {
		body = m.renderTable()
	}
	v := tea.NewView(body)
	v.AltScreen = true
	return v
}
