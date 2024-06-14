
print "Hello beautiful world"
var x := object {
    var variableX := 123456

    print ""
    print "before  variableY"
    var variableY := variableX
    print "after  variableY"
    print ""

    method foo {
        print(self.variableX)
        self.variableX := self.variableX + 789
    }
}

print "before y"
print ""

var y := x
print "after y"
print ""

x.foo()
x.foo()
x.foo()
y.foo()

print "Goodbye cruel world"
