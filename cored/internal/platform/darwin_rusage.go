//go:build darwin

package platform

/*
#include <libproc.h>
#include <stdint.h>
#include <sys/resource.h>

static int daemonitor_proc_disk_io(int pid, uint64_t *read_bytes, uint64_t *write_bytes) {
	struct rusage_info_v4 usage;
	int result = proc_pid_rusage(pid, RUSAGE_INFO_V4, (rusage_info_t)&usage);
	if (result != 0) {
		return result;
	}
	*read_bytes = usage.ri_diskio_bytesread;
	*write_bytes = usage.ri_diskio_byteswritten;
	return 0;
}
*/
import "C"

func collectDarwinDiskIO(pid int32, sampledAtMs int64) (IntMetric, IntMetric) {
	var readBytes, writeBytes C.uint64_t
	if C.daemonitor_proc_disk_io(C.int(pid), &readBytes, &writeBytes) != 0 {
		return IntUnavailable(ProbeFailed, "proc_pid_rusage"), IntUnavailable(ProbeFailed, "proc_pid_rusage")
	}
	return IntAvailable(int64(readBytes), sampledAtMs, "proc_pid_rusage"),
		IntAvailable(int64(writeBytes), sampledAtMs, "proc_pid_rusage")
}
