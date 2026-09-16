/*
 * dm-verity-open — minimal dm-verity activator for the Core3506 secure-boot
 * initramfs.
 *
 * This poky has no cryptsetup/veritysetup (no meta-openembedded) and we don't
 * want a giant quoted dm-mod.create= on the kernel cmdline (U-Boot mangles it).
 * So we create the verity mapped device directly via the device-mapper ioctls,
 * reading the (authenticated) verity params from a file baked into the initramfs
 * — which itself rides inside the SPL/U-Boot-signed boot.img FIT ramdisk node.
 *
 * Usage: dm-verity-open <conf>
 *   conf is KEY=VALUE lines: NAME, DATA_DEV, HASH_DEV, DATA_BLOCK_SIZE,
 *   HASH_BLOCK_SIZE, DATA_BLOCKS, HASH_ALGO, ROOT_HASH, SALT.
 * On success the verity device is created + resumed as /dev/mapper/<NAME>
 * (and /dev/dm-0 via devtmpfs).
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <linux/dm-ioctl.h>

#define CTRL "/dev/mapper/control"

static char name[128] = "vroot";
static char data_dev[128], hash_dev[128], hash_algo[32] = "sha256";
static char root_hash[256], salt[256];
static unsigned long data_bs = 4096, hash_bs = 4096, data_blocks = 0;
/* Hash-tree start block on the hash device. veritysetup writes a 1-block
 * superblock at block 0 by default, so the Merkle tree starts at block 1. */
static unsigned long hash_start = 1;

static void rstrip(char *s){ size_t n=strlen(s); while(n&&(s[n-1]=='\n'||s[n-1]=='\r'||s[n-1]==' ')) s[--n]=0; }

static int read_conf(const char *path)
{
	FILE *f = fopen(path, "r");
	if (!f) { fprintf(stderr, "verity: cannot open %s: %s\n", path, strerror(errno)); return -1; }
	char line[512];
	while (fgets(line, sizeof line, f)) {
		char *eq = strchr(line, '='); if (!eq) continue; *eq = 0;
		char *k = line, *v = eq + 1; rstrip(v);
		if (!strcmp(k,"NAME"))            snprintf(name,sizeof name,"%s",v);
		else if (!strcmp(k,"DATA_DEV"))   snprintf(data_dev,sizeof data_dev,"%s",v);
		else if (!strcmp(k,"HASH_DEV"))   snprintf(hash_dev,sizeof hash_dev,"%s",v);
		else if (!strcmp(k,"HASH_ALGO"))  snprintf(hash_algo,sizeof hash_algo,"%s",v);
		else if (!strcmp(k,"ROOT_HASH"))  snprintf(root_hash,sizeof root_hash,"%s",v);
		else if (!strcmp(k,"SALT"))       snprintf(salt,sizeof salt,"%s",v);
		else if (!strcmp(k,"DATA_BLOCK_SIZE")) data_bs=strtoul(v,0,10);
		else if (!strcmp(k,"HASH_BLOCK_SIZE")) hash_bs=strtoul(v,0,10);
		else if (!strcmp(k,"DATA_BLOCKS"))     data_blocks=strtoul(v,0,10);
		else if (!strcmp(k,"HASH_START"))      hash_start=strtoul(v,0,10);
	}
	fclose(f);
	if (!data_dev[0]||!hash_dev[0]||!root_hash[0]||!data_blocks) {
		fprintf(stderr, "verity: incomplete conf\n"); return -1;
	}
	return 0;
}

/* a dm_ioctl call with a fixed-size buffer */
static int dm_call(int fd, int req, struct dm_ioctl *dmi)
{
	dmi->version[0]=DM_VERSION_MAJOR; dmi->version[1]=0; dmi->version[2]=0;
	return ioctl(fd, req, dmi);
}

int main(int argc, char **argv)
{
	if (argc < 2) { fprintf(stderr, "usage: %s <conf>\n", argv[0]); return 2; }
	if (read_conf(argv[1])) return 1;

	int fd = open(CTRL, O_RDWR);
	if (fd < 0) { /* devtmpfs may not have it yet; try to create */
		mkdir("/dev/mapper", 0755);
		fd = open(CTRL, O_RDWR);
	}
	if (fd < 0) { fprintf(stderr, "verity: open %s: %s\n", CTRL, strerror(errno)); return 1; }

	char buf[16384];
	struct dm_ioctl *dmi = (void *)buf;

	/* CREATE */
	memset(buf,0,sizeof buf);
	dmi->data_size=sizeof(*dmi); dmi->data_start=sizeof(*dmi);
	snprintf(dmi->name,sizeof dmi->name,"%s",name);
	if (dm_call(fd, DM_DEV_CREATE, dmi)) { perror("DM_DEV_CREATE"); return 1; }

	/* TABLE LOAD: one verity target spanning the whole data area */
	memset(buf,0,sizeof buf);
	dmi->data_size=sizeof buf; dmi->data_start=sizeof(*dmi);
	snprintf(dmi->name,sizeof dmi->name,"%s",name);
	dmi->target_count=1; dmi->flags=DM_READONLY_FLAG;
	struct dm_target_spec *ts = (void *)(buf + sizeof(*dmi));
	ts->sector_start=0;
	ts->length=(unsigned long long)data_blocks * data_bs / 512;
	snprintf(ts->target_type,sizeof ts->target_type,"verity");
	char *params = (char *)(ts+1);
	int n = snprintf(params, buf+sizeof buf-(params),
		"1 %s %s %lu %lu %lu %lu %s %s %s",
		data_dev, hash_dev, data_bs, hash_bs, data_blocks,
		hash_start, hash_algo, root_hash, salt);
	/* dm_target_spec.next = offset (from dm_ioctl start) to the next target,
	 * i.e. just past this target's params, 8-byte aligned. */
	unsigned long long off = sizeof(*dmi) + sizeof(*ts) + n + 1;
	off = (off + 7) & ~7ULL;
	ts->next = off;
	if (dm_call(fd, DM_TABLE_LOAD, dmi)) { perror("DM_TABLE_LOAD"); return 1; }

	/* RESUME (activate) */
	memset(buf,0,sizeof buf);
	dmi->data_size=sizeof(*dmi); dmi->data_start=sizeof(*dmi);
	snprintf(dmi->name,sizeof dmi->name,"%s",name);
	if (dm_call(fd, DM_DEV_SUSPEND, dmi)) { perror("DM_DEV_SUSPEND(resume)"); return 1; }

	fprintf(stderr, "verity: /dev/mapper/%s active (%lu x %lu-byte blocks)\n",
		name, data_blocks, data_bs);
	return 0;
}
