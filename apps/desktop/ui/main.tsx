// Point GPUIX at Aurelia's renderer before anything loads it. During
// development that is the last published build; a compiled binary carries
// its own.
import { latestBinding } from "../scripts/paths";

const binding = process.env.AURELIA_NATIVE_LIBRARY ?? latestBinding();
if (binding) process.env.NAPI_RS_NATIVE_LIBRARY_PATH = binding;
await import("./bootstrap");
