// The local server of the device tests: endpoints to test redirects, limits
// and bodies against, and /results, which writes the results that an app
// posts to the file given as the first argument.
import http from 'node:http';
import fs from 'node:fs';

const resultsFile = process.argv[2];
const port = Number(process.argv[3] || 8771);
const feed = '<rss version="2.0"><channel><title>Local</title><item><title>One</title><guid>1</guid></item></channel></rss>';

http.createServer((req, res) => {
  const chunks = [];
  req.on('data', c => chunks.push(c));
  req.on('end', () => {
    const body = Buffer.concat(chunks);
    console.log(new Date().toISOString(), req.method, req.url, body.length);
    switch (req.url) {
      case '/feed.xml':
        res.writeHead(200, {'Content-Type': 'application/rss+xml'});
        return res.end(feed);
      case '/redirect':
        res.writeHead(302, {'Location': '/feed.xml'});
        return res.end();
      case '/big':
        res.writeHead(200, {'Content-Type': 'application/octet-stream'});
        return res.end(Buffer.alloc(2 * 1024 * 1024, 120));
      // long after the 2 seconds that the checks allow, even when the app's
      // thread is busy with the other checks
      case '/slow':
        return setTimeout(() => { res.writeHead(200); res.end('late'); }, 15000);
      case '/json':
        res.writeHead(200, {'Content-Type': 'application/json'});
        return res.end('{"a": [1, 2]}');
      case '/latin1':
        res.writeHead(200, {'Content-Type': 'text/plain; charset=iso-8859-1'});
        return res.end(Buffer.from('Æblegrød', 'latin1'));
      case '/ua':
        res.writeHead(200, {'Content-Type': 'text/plain'});
        return res.end(req.headers['user-agent'] || '');
      case '/echo':
        res.writeHead(200, {'Content-Type': 'text/plain'});
        return res.end(`${req.method} ${body.length}`);
      case '/results':
        fs.writeFileSync(resultsFile, body);
        res.writeHead(204);
        return res.end();
      default:
        res.writeHead(404);
        return res.end('no');
    }
  });
}).listen(port, '127.0.0.1', () => console.log(`listening on ${port}`));
