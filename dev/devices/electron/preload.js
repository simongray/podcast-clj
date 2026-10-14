// The bridge between the page and the main process, as wary-fetch's
// electron namespace describes it.
const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("waryFetch", {
  send: (request) => ipcRenderer.invoke("wary-fetch", request),
  sendLocal: (request) => ipcRenderer.invoke("wary-fetch-local", request),
  origin: () => ipcRenderer.invoke("local-origin"),
  report: (results) => ipcRenderer.send("results", results),
});
