`It's a cycle of life`
======================

Working examples for slides at
https://slides.com/seigert/recycle

To run this examples you'll need [`scala-cli`]:

```console
$> scala-cli run project.scala 4.resource.sc
 -- Printer 'log' is acquired.
log: will sum x = 2 and y = 2
 -- Printer 'log' is released

4$u002Eresource$_.this.sum(2, 2) = 4

 -- Printer 'log' is acquired.
log: will sum 10 ranges [i..10] where i in [0..10]
log: will sum range [0, 10]
log: will sum range [1, 10]
log: will sum range [2, 10]
log: will sum range [3, 10]
log: will sum range [4, 10]
log: will sum range [5, 10]
log: will sum range [6, 10]
log: will sum range [7, 10]
log: will sum range [8, 10]
log: will sum range [9, 10]
log: will sum range [10, 10]
 -- Printer 'metrics' is acquired.
metrics: collected metrics:
metrics:   sum -> 311.97 ± 425.51 (66 samples)
metrics:   vector -> 691875.45 ± 1836794.82 (11 samples)
 -- Printer 'metrics' is released
 -- Printer 'log' is released
 ...
 ```

[`scala-cli`]: https://scala-cli.virtuslab.org/
