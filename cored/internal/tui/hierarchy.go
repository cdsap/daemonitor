package tui

import (
	"sort"

	"github.com/cdsap/daemonitor/cored/internal/model"
)

// processIdentity is deliberately stronger than a PID. Operating systems can
// reuse a PID while a monitor is running, so remembered ancestry must include
// the process start time whenever it is available.
type processIdentity struct {
	pid   int32
	start int64
}

func identity(p model.Process) processIdentity {
	return processIdentity{pid: p.PID, start: p.StartTimeMs}
}

type displayRow struct {
	process   model.Process
	target    model.Process
	depth     int
	groupRoot bool
	child     bool
	children  int
}

// processHierarchy remembers only relationships that were observed while the
// parent was present. This lets a worker remain attached to its daemon after a
// transient reparenting, without guessing across a PID reuse.
type processHierarchy struct {
	parents  map[processIdentity]processIdentity
	expanded map[processIdentity]bool
}

func newProcessHierarchy() processHierarchy {
	return processHierarchy{
		parents:  make(map[processIdentity]processIdentity),
		expanded: make(map[processIdentity]bool),
	}
}

func (h *processHierarchy) observe(processes []model.Process) {
	current := make(map[processIdentity]processIdentity, len(processes))
	byPID := make(map[int32]processIdentity, len(processes))
	for _, p := range processes {
		id := identity(p)
		byPID[p.PID] = id
	}
	for _, p := range processes {
		if parent, ok := byPID[p.ParentPID]; ok && p.PID != p.ParentPID {
			current[identity(p)] = parent
		}
	}
	for child, parent := range current {
		if _, known := h.parents[child]; !known {
			h.parents[child] = parent
		}
	}
	// A PID/start identity is immutable. Drop old entries once their process
	// identity is absent; retained entries for currently alive workers are kept
	// even when their parent is no longer in the filtered process list.
	alive := make(map[processIdentity]struct{}, len(processes))
	for _, p := range processes {
		alive[identity(p)] = struct{}{}
	}
	for child := range h.parents {
		if _, ok := alive[child]; !ok {
			delete(h.parents, child)
		}
	}
}

func (h processHierarchy) owner(id processIdentity, byID map[processIdentity]model.Process) (processIdentity, bool) {
	seen := map[processIdentity]bool{}
	for {
		if seen[id] {
			return processIdentity{}, false
		}
		seen[id] = true
		p, ok := byID[id]
		if ok && p.Type == "GRADLE_DAEMON" {
			return id, true
		}
		parent, ok := h.parents[id]
		if !ok {
			return processIdentity{}, false
		}
		id = parent
	}
}

func aggregateGroup(root model.Process, children []model.Process) model.Process {
	aggregate := root
	aggregate.RSSMemoryMB = root.RSSMemoryMB
	var cpu float64
	cpuAvailable := false
	if root.CPUPercent != nil {
		cpu = *root.CPUPercent
		cpuAvailable = true
	}
	for _, child := range children {
		aggregate.RSSMemoryMB += child.RSSMemoryMB
		if child.CPUPercent != nil {
			cpu += *child.CPUPercent
			cpuAvailable = true
		}
	}
	if cpuAvailable {
		aggregate.CPUPercent = &cpu
	} else {
		aggregate.CPUPercent = nil
	}
	return aggregate
}

func (h *processHierarchy) rows(processes []model.Process, expandedDefault bool) []displayRow {
	h.observe(processes)
	byID := make(map[processIdentity]model.Process, len(processes))
	for _, p := range processes {
		byID[identity(p)] = p
	}
	childrenByOwner := make(map[processIdentity][]model.Process)
	for _, p := range processes {
		id := identity(p)
		if p.Type == "GRADLE_DAEMON" {
			continue
		}
		if owner, ok := h.owner(id, byID); ok && owner != id {
			childrenByOwner[owner] = append(childrenByOwner[owner], p)
		}
	}

	rows := make([]displayRow, 0, len(processes))
	grouped := make(map[processIdentity]bool)
	groupedRoots := make(map[processIdentity]bool)
	for _, root := range processes {
		if root.Type != "GRADLE_DAEMON" {
			continue
		}
		rootID := identity(root)
		children := childrenByOwner[rootID]
		if len(children) == 0 {
			continue
		}
		sort.SliceStable(children, func(i, j int) bool { return children[i].PID < children[j].PID })
		for _, child := range children {
			grouped[identity(child)] = true
		}
		groupedRoots[rootID] = true
		if _, ok := h.expanded[rootID]; !ok {
			h.expanded[rootID] = expandedDefault
		}
		rows = append(rows, displayRow{process: aggregateGroup(root, children), target: root, groupRoot: true, children: len(children)})
		if h.expanded[rootID] {
			for _, child := range children {
				rows = append(rows, displayRow{process: child, target: child, depth: 1, child: true})
			}
		}
	}
	for _, p := range processes {
		id := identity(p)
		if groupedRoots[id] || grouped[id] {
			continue
		}
		rows = append(rows, displayRow{process: p, target: p})
	}
	return rows
}

func (h *processHierarchy) setExpanded(id processIdentity, expanded bool) {
	h.expanded[id] = expanded
}
